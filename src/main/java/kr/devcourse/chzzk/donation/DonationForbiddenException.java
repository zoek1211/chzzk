package kr.devcourse.chzzk.donation;

/** 후원한 본인이 아닌 회원이 주문의 결제 결과를 요청했다(403). 주문 정보는 밖으로 내지 않는다. */
class DonationForbiddenException extends RuntimeException {
}
