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
$password = (Get-Content (Join-Path $root '.env') | Where-Object { $_ -match '^PAYASSIST_DEMO_PASSWORD=' }) -replace '^PAYASSIST_DEMO_PASSWORD=', ''
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
Check 'agent cannot see supervisor stats (403)' ((Status $alice.Session '/api/v1/payments/stats') -eq 403)
Check 'agent cannot see audit log (403)' ((Status $alice.Session '/api/v1/admin/audit-events') -eq 403)

Write-Host "`n[3] Login as sam.supervisor (CARE_SUPERVISOR)"
$sam = Login 'sam.supervisor'
Check 'supervisor can see stats' ((Status $sam.Session '/api/v1/payments/stats') -eq 200)
Check 'supervisor cannot see audit log (403)' ((Status $sam.Session '/api/v1/admin/audit-events') -eq 403)

Write-Host "`n[4] Login as ada.admin (ADMIN)"
$ada = Login 'ada.admin'
Check 'admin can see stats' ((Status $ada.Session '/api/v1/payments/stats') -eq 200)
$audit = (Send $ada.Session 'GET' "$BaseUrl/api/v1/admin/audit-events" $null @{ Accept = 'application/json' }).Content.ReadAsStringAsync().GetAwaiter().GetResult()
Check 'audit log recorded agent.alice viewing PAY-000042' ($audit -match 'agent\.alice' -and $audit -match 'PAY-000042')

Write-Host "`n[5] Login as nora.norole (no PayAssist role)"
$nora = Login 'nora.norole'
Check 'user without role can call /me' ((Status $nora.Session '/api/v1/me') -eq 200)
Check 'user without role is denied payments (403)' ((Status $nora.Session '/api/v1/payments') -eq 403)

Write-Host "`n[6] CSRF and logout (agent.alice)"
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
