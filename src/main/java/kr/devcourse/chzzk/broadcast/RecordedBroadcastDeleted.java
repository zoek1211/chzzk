package kr.devcourse.chzzk.broadcast;

/** 녹화방송이 삭제되었다. 같은 트랜잭션 안에서 발행되므로, 댓글 모듈이 이 방송의 댓글을 함께 지우는 데 쓴다. */
public record RecordedBroadcastDeleted(Long broadcastId) {
}
