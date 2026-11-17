package com.payassist.payment;

/** How the customer paid. PayAssist never stores card numbers or bank account details. */
public enum PaymentMethod {
    CARD,
    BANK_TRANSFER,
    WALLET
}
