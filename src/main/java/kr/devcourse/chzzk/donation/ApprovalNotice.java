package kr.devcourse.chzzk.donation;

/** 승인 통지가 들어온 경로. 리다이렉트는 후원자 본인의 브라우저, 웹훅은 결제사 서버다(본문을 믿지 않는다). */
enum ApprovalNotice {
    REDIRECT, WEBHOOK
}
