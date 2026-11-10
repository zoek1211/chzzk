package kr.devcourse.chzzk.broadcast;

/** 수정 요청의 제목이 규칙을 어겼을 때. 수정 화면을 다시 보여 주려고 어느 방송인지 함께 싣는다. */
public class InvalidRenameException extends InvalidRegistrationException {

    private final Long broadcastId;

    public InvalidRenameException(Long broadcastId, String message) {
        super(message);
        this.broadcastId = broadcastId;
    }

    public Long broadcastId() {
        return broadcastId;
    }
}
