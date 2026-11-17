package com.payassist.payment.internal;

import com.payassist.payment.PaymentStatus;

public record StatusCount(PaymentStatus status, long count) {
}
