package kr.devcourse.chzzk.live;

/** 종료된 라이브 방송이 삭제되었다. 같은 트랜잭션 안에서 발행되므로, 댓글 모듈이 이 라이브의 댓글을 함께 지우는 데 쓸 수 있다. */
public record LiveDeleted(Long liveId) {
}
