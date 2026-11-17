<#
.SYNOPSIS
  End-to-end check of the running stack: real Keycloak login (Authorization Code + PKCE through
  the BFF), session cookie flags, role-based authorization, CSRF and logout.
.DESCRIPTION
  Logs in as each synthetic user by submitting the Keycloak login form, exactly as a browser would,
  then calls the API with the resulting session cookie. Requires the stack to be up
  (docker compose up -d) and .env to exist.
.EXAMPLE
  .\scripts\smoke-test.ps1
  .\scripts\smoke-test.ps1 -BaseUrl http://localhost:5173   # against the Vite dev server
#>
param([string]$BaseUrl = 'http://localhost:3000')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
# Parse like Docker Compose does: surrounding whitespace on the line is ignored.
$password = (Get-Content (Join-Path $root '.env') | Where-Object { $_ -match '^\s*PAYASSIST_DEMO_PASSWORD=' } |
    Select-Object -First 1) -replace '^\s*PAYASSIST_DEMO_PASSWORD=', ''
if ($password) { $password = $password.Trim() }
if (-not $password) { throw 'PAYASSIST_DEMO_PASSWORD not found in .env' }

Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Web
$script:failures = 0

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) { Write-Host "  PASS  $name" -ForegroundColor Green }
    else { Write-Host "  FAIL  $name $detail" -ForegroundColor Red; $script:failures++ }
}

function New-Client {
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.CookieContainer = New-Object System.Net.CookieContainer
    $handler.AllowAutoRedirect = $false   # follow manually to inspect every hop
    $client = New-Object System.Net.Http.HttpClient($handler)
    return @{ Client = $client; Cookies = $handler.CookieContainer }
}

function Send($session, [string]$method, [string]$url, $form = $null, $headers = @{}) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($method), $url)
    foreach ($k in $headers.Keys) { [void]$request.Headers.TryAddWithoutValidation($k, $headers[$k]) }
    if ($form) {
        $pairs = New-Object 'System.Collections.Generic.List[System.Collections.Generic.KeyValuePair[string,string]]'
        foreach ($k in $form.Keys) { $pairs.Add((New-Object 'System.Collections.Generic.KeyValuePair[string,string]' $k, $form[$k])) }
        $request.Content = [System.Net.Http.FormUrlEncodedContent]::new($pairs)
    }
    $response = $session.Client.SendAsync($request).GetAwaiter().GetResult()
    Copy-LocalhostSecureCookies $session $response
    return $response
}

# JSON POST to the API, echoing the session's XSRF-TOKEN cookie in the X-XSRF-TOKEN header (as the SPA does).
function PostJson($session, [string]$path, [string]$json, [bool]$withCsrf = $true, $headers = @{}) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$BaseUrl$path")
    $request.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
    [void]$request.Headers.TryAddWithoutValidation('Accept', 'application/json')
    foreach ($k in $headers.Keys) { [void]$request.Headers.TryAddWithoutValidation($k, $headers[$k]) }
    if ($withCsrf) {
        $token = ($session.Cookies.GetCookies([Uri]$BaseUrl) | Where-Object Name -eq 'XSRF-TOKEN').Value
        [void]$request.Headers.TryAddWithoutValidation('X-XSRF-TOKEN', $token)
    }
    return $session.Client.SendAsync($request).GetAwaiter().GetResult()
}

# Browsers treat http://localhost as a secure context and send 'Secure' cookies to it (Keycloak's
# login cookies are Secure; SameSite=None). .NET does not, so mirror browser behaviour for localhost only.
function Copy-LocalhostSecureCookies($session, $response) {
    $uri = $response.RequestMessage.RequestUri
    if ($uri.Host -ne 'localhost' -or -not $response.Headers.Contains('Set-Cookie')) { return }
    foreach ($header in $response.Headers.GetValues('Set-Cookie')) {
        if ($header -notmatch ';\s*Secure') { continue }
        $nameValue = ($header -split ';')[0]
        $name, $value = $nameValue -split '=', 2
        $path = if ($header -match ';\s*Path=([^;]+)') { $Matches[1] } else { '/' }
        $cookie = New-Object System.Net.Cookie($name.Trim(), $value.Trim('"'), $path, $uri.Host)
        if ($header -match 'Max-Age=0' -or $header -match 'Expires=Thu, 01[- ]Jan[- ]1970') { $cookie.Expired = $true }
        $session.Cookies.Add($uri, $cookie)
    }
}

function Follow($session, $response, [int]$max = 10) {
    $hops = @()
    while ([int]$response.StatusCode -ge 300 -and [int]$response.StatusCode -lt 400 -and $max-- -gt 0) {
        $location = $response.Headers.Location
        if (-not $location.IsAbsoluteUri) { $location = New-Object System.Uri($response.RequestMessage.RequestUri, $location) }
        $hops += $location.AbsoluteUri
        $response = Send $session 'GET' $location.AbsoluteUri
    }
    return @{ Response = $response; Hops = $hops }
}

function Login([string]$username) {
    $session = New-Client
    $start = Send $session 'GET' "$BaseUrl/oauth2/authorization/keycloak"
    $authUrl = $start.Headers.Location.AbsoluteUri
    $page = Send $session 'GET' $authUrl
    $html = $page.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if ($html -notmatch 'action="([^"]+)"') { throw "Keycloak login form not found for $username" }
    $action = [System.Web.HttpUtility]::HtmlDecode($Matches[1])
    $posted = Send $session 'POST' $action @{ username = $username; password = $password; credentialId = '' }
    $result = Follow $session $posted
    return @{ Session = $session; AuthUrl = $authUrl; Final = $result.Response; Hops = $result.Hops; StartResponse = $start }
}

function Status($session, [string]$path) {
    return [int](Send $session 'GET' "$BaseUrl$path" $null @{ Accept = 'application/json' }).StatusCode
}

Write-Host "PayAssist smoke test against $BaseUrl" -ForegroundColor Cyan

Write-Host "`n[1] Anonymous access"
$anon = New-Client
Check 'health endpoint is public' ((Status $anon '/actuator/health') -eq 200)
Check 'API rejects anonymous calls with 401' ((Status $anon '/api/v1/payments') -eq 401)
Check 'SPA is served' ((Status $anon '/') -eq 200)

Write-Host "`n[2] Login as agent.alice (CARE_AGENT)"
$alice = Login 'agent.alice'
$query = [System.Web.HttpUtility]::ParseQueryString(([Uri]$alice.AuthUrl).Query)
Check 'authorization request uses PKCE S256' ($query['code_challenge_method'] -eq 'S256' -and $query['code_challenge'])
Check 'authorization request has state and nonce' ($query['state'] -and $query['nonce'])
Check 'no client secret in browser-visible URL' ($alice.AuthUrl -notmatch 'secret')
Check 'login redirects back to the SPA' (($alice.Hops | Select-Object -Last 1) -eq "$BaseUrl/")
$sessionCookie = $alice.Session.Cookies.GetCookies([Uri]$BaseUrl) | Where-Object Name -eq 'PAYASSIST_SESSION'
Check 'session cookie issued' ($null -ne $sessionCookie)
Check 'session cookie is HttpOnly' ($sessionCookie.HttpOnly)
$me = (Send $alice.Session 'GET' "$BaseUrl/api/v1/me" $null @{ Accept = 'application/json' }).Content.ReadAsStringAsync().GetAwaiter().GetResult()
Check '/me returns CARE_AGENT and no tokens' ($me -match '"CARE_AGENT"' -and $me -notmatch 'eyJ')
Check 'agent can search payments' ((Status $alice.Session '/api/v1/payments?q=PAY-0000') -eq 200)
Check 'agent can open a payment' ((Status $alice.Session '/api/v1/payments/PAY-000042') -eq 200)
Check 'agent can view the payment timeline' ((Status $alice.Session '/api/v1/payments/PAY-000042/timeline') -eq 200)
Check 'agent can view the investigation summary' ((Status $alice.Session '/api/v1/payments/PAY-000042/investigation-summary') -eq 200)
Check 'agent cannot see supervisor stats (403)' ((Status $alice.Session '/api/v1/payments/stats') -eq 403)
Check 'agent cannot see audit log (403)' ((Status $alice.Session '/api/v1/admin/audit-events') -eq 403)

Write-Host "`n[3] Login as sam.supervisor (CARE_SUPERVISOR)"
$sam = Login 'sam.supervisor'
Check 'supervisor can see stats' ((Status $sam.Session '/api/v1/payments/stats') -eq 200)
Check 'supervisor cannot see audit log (403)' ((Status $sam.Session '/api/v1/admin/audit-events') -eq 403)

Write-Host "`n[4] Login as ada.admin (ADMIN)"
$ada = Login 'ada.admin'
Check 'admin can see stats' ((Status $ada.Session '/api/v1/payments/stats') -eq 200)
Check 'admin has read-only investigation access' ((Status $ada.Session '/api/v1/payments/PAY-000042/investigation-summary') -eq 200)
$audit = (Send $ada.Session 'GET' "$BaseUrl/api/v1/admin/audit-events" $null @{ Accept = 'application/json' }).Content.ReadAsStringAsync().GetAwaiter().GetResult()
Check 'audit log recorded agent.alice viewing PAY-000042' ($audit -match 'agent\.alice' -and $audit -match 'PAY-000042')

Write-Host "`n[5] Login as nora.norole (no PayAssist role)"
$nora = Login 'nora.norole'
Check 'user without role can call /me' ((Status $nora.Session '/api/v1/me') -eq 200)
Check 'user without role is denied payments (403)' ((Status $nora.Session '/api/v1/payments') -eq 403)
Check 'user without role is denied the timeline (403)' ((Status $nora.Session '/api/v1/payments/PAY-000042/timeline') -eq 403)

Write-Host "`n[6] Refund eligibility (read-only check)"
$eligibilityBody = '{"paymentRef":"PAY-000042"}'
Check 'eligibility check without CSRF token is rejected (403)' ([int](PostJson $alice.Session '/api/v1/refunds/eligibility-check' $eligibilityBody $false).StatusCode -eq 403)
$eligibility = PostJson $alice.Session '/api/v1/refunds/eligibility-check' $eligibilityBody
$eligibilityJson = $eligibility.Content.ReadAsStringAsync().GetAwaiter().GetResult()
Check 'agent gets an eligibility decision with policy version' ([int]$eligibility.StatusCode -eq 200 -and $eligibilityJson -match '"decision":"(ELIGIBLE|INELIGIBLE|MANUAL_REVIEW)"' -and $eligibilityJson -match '"policyVersion":"[^"]+"')
Check 'user without role is denied eligibility checks (403)' ([int](PostJson $nora.Session '/api/v1/refunds/eligibility-check' $eligibilityBody).StatusCode -eq 403)
$audit = (Send $ada.Session 'GET' "$BaseUrl/api/v1/admin/audit-events?size=5").Content.ReadAsStringAsync().GetAwaiter().GetResult()
Check 'eligibility check recorded in the audit log' ($audit -match 'REFUND_ELIGIBILITY_CHECK')

Write-Host "`n[7] Refund requests and supervisor approval (no money moves; requests are rejected, not approved)"
function Body($response) { return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
$tara = Login 'tara.supervisor'
Check 'second supervisor tara.supervisor can sign in' ((Status $tara.Session '/api/v1/me') -eq 200)
$taraMe = Body (Send $tara.Session 'GET' "$BaseUrl/api/v1/me" $null @{ Accept = 'application/json' })
Check 'tara.supervisor has CARE_SUPERVISOR' ($taraMe -match '"CARE_SUPERVISOR"')
Check 'sam.supervisor can still sign in' ((Status $sam.Session '/api/v1/me') -eq 200)

# Find a payment that is eligible for a small partial refund right now (the window depends on today's date).
$candidate = $null
$settled = (Body (Send $alice.Session 'GET' "$BaseUrl/api/v1/payments?status=SETTLED&size=100" $null @{ Accept = 'application/json' })) | ConvertFrom-Json
foreach ($p in $settled.content) {
    $check = (Body (PostJson $alice.Session '/api/v1/refunds/eligibility-check' ('{"paymentRef":"' + $p.paymentRef + '","amount":"1.00","currency":"' + $p.currency + '"}'))) | ConvertFrom-Json
    if ($check.decision -eq 'ELIGIBLE') { $candidate = $p; break }
}
Check 'found a payment eligible for a 1.00 refund' ($null -ne $candidate)
if ($candidate) {
    $refundBody = '{"paymentRef":"' + $candidate.paymentRef + '","amount":"1.00","currency":"' + $candidate.currency + '","reason":"CUSTOMER_REQUEST"}'
    $key = 'smoke-' + [guid]::NewGuid()
    Check 'refund request without Idempotency-Key is rejected (400)' ([int](PostJson $alice.Session '/api/v1/refunds' $refundBody).StatusCode -eq 400)
    Check 'refund request without CSRF token is rejected (403)' ([int](PostJson $alice.Session '/api/v1/refunds' $refundBody $false @{ 'Idempotency-Key' = $key }).StatusCode -eq 403)
    $created = PostJson $alice.Session '/api/v1/refunds' $refundBody $true @{ 'Idempotency-Key' = $key }
    $aliceRefund = (Body $created) | ConvertFrom-Json
    Check 'agent creates a refund request (201, PENDING_APPROVAL)' ([int]$created.StatusCode -eq 201 -and $aliceRefund.status -eq 'PENDING_APPROVAL')
    $replay = PostJson $alice.Session '/api/v1/refunds' $refundBody $true @{ 'Idempotency-Key' = $key }
    Check 'retry with the same key replays the original (200, Idempotent-Replayed)' ([int]$replay.StatusCode -eq 200 -and (($replay.Headers.GetValues('Idempotent-Replayed')) -contains 'true') -and ((Body $replay) -match $aliceRefund.refundRef))
    $ref = $aliceRefund.refundRef
    Check 'agent cannot approve (403)' ([int](PostJson $alice.Session "/api/v1/refunds/$ref/approve" '{}').StatusCode -eq 403)
    Check 'admin cannot approve (403)' ([int](PostJson $ada.Session "/api/v1/refunds/$ref/approve" '{}').StatusCode -eq 403)
    Check 'admin cannot create refund requests (403)' ([int](PostJson $ada.Session '/api/v1/refunds' $refundBody $true @{ 'Idempotency-Key' = ('smoke-' + [guid]::NewGuid()) }).StatusCode -eq 403)
    Check 'admin can read refund requests (read-only)' ((Status $ada.Session "/api/v1/refunds/$ref") -eq 200)

    $samRequest = (Body (PostJson $sam.Session '/api/v1/refunds' $refundBody $true @{ 'Idempotency-Key' = ('smoke-' + [guid]::NewGuid()) })) | ConvertFrom-Json
    $self = PostJson $sam.Session "/api/v1/refunds/$($samRequest.refundRef)/approve" '{}'
    Check 'supervisor cannot approve their own request (403 SELF_APPROVAL_FORBIDDEN)' ([int]$self.StatusCode -eq 403 -and (Body $self) -match 'SELF_APPROVAL_FORBIDDEN')
    $taraReject = PostJson $tara.Session "/api/v1/refunds/$($samRequest.refundRef)/reject" '{"reason":"CUSTOMER_WITHDREW"}'
    Check 'the other supervisor (tara) can decide on it' ([int]$taraReject.StatusCode -eq 200 -and (Body $taraReject) -match '"status":"REJECTED"')
    $samReject = PostJson $sam.Session "/api/v1/refunds/$ref/reject" '{"reason":"CUSTOMER_WITHDREW"}'
    Check "supervisor rejects the agent's request; reservation released" ([int]$samReject.StatusCode -eq 200)
    Check 'a decided request cannot be decided again (409)' ([int](PostJson $tara.Session "/api/v1/refunds/$ref/approve" '{}').StatusCode -eq 409)
    $audit = Body (Send $ada.Session 'GET' "$BaseUrl/api/v1/admin/audit-events?size=40" $null @{ Accept = 'application/json' })
    Check 'refund actions and denials are in the audit log' ($audit -match 'REFUND_REQUEST_CREATE' -and $audit -match 'REFUND_REQUEST_REJECT' -and $audit -match '"outcome":"DENIED"')
}

Write-Host "`n[8] Dispute cases (no money moves; both cases end with the payment restored)"
function Version($response) { return ((Body $response) | ConvertFrom-Json).version }
function DisputeAction($session, [string]$ref, [string]$action, [string]$json) { return PostJson $session "/api/v1/disputes/$ref/$action" $json }
$oldest = (Body (Send $alice.Session 'GET' "$BaseUrl/api/v1/payments?status=SETTLED&size=100" $null @{ Accept = 'application/json' })) | ConvertFrom-Json
$paymentA = $oldest.content[-1].paymentRef
$paymentB = $oldest.content[-2].paymentRef
Check 'found two SETTLED payments for dispute checks' ($paymentA -and $paymentB)

# Case A: agent opens and works the case; another supervisor approves the evidence; a third person records WON.
$openA = PostJson $alice.Session '/api/v1/disputes' ('{"paymentRef":"' + $paymentA + '","reasonCategory":"PRODUCT_NOT_RECEIVED"}') $true @{ 'Idempotency-Key' = ('smoke-' + [guid]::NewGuid()) }
$caseA = (Body $openA) | ConvertFrom-Json
Check 'agent opens a dispute case (201, OPEN)' ([int]$openA.StatusCode -eq 201 -and $caseA.status -eq 'OPEN')
$elig = (Body (PostJson $alice.Session '/api/v1/refunds/eligibility-check' ('{"paymentRef":"' + $paymentA + '"}'))) | ConvertFrom-Json
Check 'refunds are blocked while the dispute is active (PAYMENT_DISPUTED)' ($elig.reasons.code -contains 'PAYMENT_DISPUTED')
$refA = $caseA.caseRef
$v = Version (DisputeAction $alice.Session $refA 'start-investigation' '{"expectedVersion":0}')
$ev = PostJson $alice.Session "/api/v1/disputes/$refA/evidence" '{"type":"PROOF_OF_DELIVERY","documentReference":"SMOKE-DOC-1"}' $true @{ 'Idempotency-Key' = ('smoke-' + [guid]::NewGuid()) }
$noteBlocked = PostJson $alice.Session "/api/v1/disputes/$refA/notes" '{"body":"card 4111 1111 1111 1111"}' $true @{ 'Idempotency-Key' = ('smoke-' + [guid]::NewGuid()) }
Check 'notes containing a card number are rejected (422 CARD_NUMBER_DETECTED)' ([int]$noteBlocked.StatusCode -eq 422 -and (Body $noteBlocked) -match 'CARD_NUMBER_DETECTED')
$v = Version (PostJson $alice.Session "/api/v1/disputes/$refA/notes" '{"body":"Smoke test note."}' $true @{ 'Idempotency-Key' = ('smoke-' + [guid]::NewGuid()) })
$review = DisputeAction $alice.Session $refA 'request-review' ('{"expectedVersion":' + $v + '}')
Check 'agent adds evidence and requests supervisor review' ([int]$ev.StatusCode -eq 200 -and [int]$review.StatusCode -eq 200)
$v = Version $review
Check 'agent cannot approve the evidence submission (403)' ([int](DisputeAction $alice.Session $refA 'approve-submission' ('{"expectedVersion":' + $v + '}')).StatusCode -eq 403)
Check 'admin cannot approve the evidence submission (403)' ([int](DisputeAction $ada.Session $refA 'approve-submission' ('{"expectedVersion":' + $v + '}')).StatusCode -eq 403)
Check 'admin can read the case (read-only)' ((Status $ada.Session "/api/v1/disputes/$refA") -eq 200)
$approved = DisputeAction $sam.Session $refA 'approve-submission' ('{"expectedVersion":' + $v + '}')
Check 'supervisor sam approves the submission' ([int]$approved.StatusCode -eq 200)
$won = DisputeAction $tara.Session $refA 'record-outcome' ('{"outcome":"WON","expectedVersion":' + (Version $approved) + '}')
Check 'supervisor tara records WON' ([int]$won.StatusCode -eq 200 -and (Body $won) -match '"status":"WON"')
$paymentAfter = (Body (Send $alice.Session 'GET' "$BaseUrl/api/v1/payments/$paymentA" $null @{ Accept = 'application/json' })) | ConvertFrom-Json
Check 'payment is restored to SETTLED after the won dispute' ($paymentAfter.status -eq 'SETTLED')

# Case B: separation of duties on the review, then withdrawal restores the payment.
$caseB = (Body (PostJson $sam.Session '/api/v1/disputes' ('{"paymentRef":"' + $paymentB + '","reasonCategory":"DUPLICATE_CHARGE"}') $true @{ 'Idempotency-Key' = ('smoke-' + [guid]::NewGuid()) })) | ConvertFrom-Json
$refB = $caseB.caseRef
$v = Version (DisputeAction $sam.Session $refB 'start-investigation' '{"expectedVersion":0}')
$v = Version (PostJson $sam.Session "/api/v1/disputes/$refB/evidence" '{"type":"TRANSACTION_RECEIPT","documentReference":"SMOKE-DOC-2"}' $true @{ 'Idempotency-Key' = ('smoke-' + [guid]::NewGuid()) })
$v = Version (DisputeAction $sam.Session $refB 'request-review' ('{"expectedVersion":' + $v + '}'))
$selfReview = DisputeAction $sam.Session $refB 'approve-submission' ('{"expectedVersion":' + $v + '}')
Check 'supervisor cannot approve a review they requested (403 SELF_REVIEW_FORBIDDEN)' ([int]$selfReview.StatusCode -eq 403 -and (Body $selfReview) -match 'SELF_REVIEW_FORBIDDEN')
$withdrawn = DisputeAction $tara.Session $refB 'withdraw' ('{"reason":"RAISED_IN_ERROR","expectedVersion":' + $v + '}')
Check 'tara records the withdrawal; payment restored' ([int]$withdrawn.StatusCode -eq 200 -and (Body $withdrawn) -match '"status":"WITHDRAWN"')
$audit = Body (Send $ada.Session 'GET' "$BaseUrl/api/v1/admin/audit-events?size=60" $null @{ Accept = 'application/json' })
Check 'dispute actions and denials are in the audit log' ($audit -match 'DISPUTE_CASE_OPEN' -and $audit -match 'DISPUTE_CASE_TRANSITION' -and $audit -match 'SELF_REVIEW_FORBIDDEN')

Write-Host "`n[9] CSRF and logout (agent.alice)"
$csrf = ($alice.Session.Cookies.GetCookies([Uri]$BaseUrl) | Where-Object Name -eq 'XSRF-TOKEN').Value
Check 'XSRF-TOKEN cookie present' ([bool]$csrf)
Check 'logout without CSRF token is rejected (403)' ([int](Send $alice.Session 'POST' "$BaseUrl/logout").StatusCode -eq 403)
$logout = Send $alice.Session 'POST' "$BaseUrl/logout" @{ _csrf = $csrf }
$logoutLocation = [string]$logout.Headers.Location
Check 'logout redirects to Keycloak end-session' ($logoutLocation -match '/realms/payassist/protocol/openid-connect/logout' -and $logoutLocation -match 'id_token_hint')
Check 'session is invalid after logout (401)' ((Status $alice.Session '/api/v1/payments') -eq 401)

Write-Host ''
if ($script:failures -gt 0) { Write-Host "$($script:failures) check(s) FAILED" -ForegroundColor Red; exit 1 }
Write-Host 'All smoke checks passed' -ForegroundColor Green
