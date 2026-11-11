package kr.devcourse.chzzk.donation;

/** 후원 주문 상태. PENDING에서 한 번만 나머지 셋 중 하나로 바뀐다. */
public enum DonationStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    CANCELED
}
