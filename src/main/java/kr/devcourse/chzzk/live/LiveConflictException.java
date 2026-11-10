package kr.devcourse.chzzk.live;

/** 지금 상태에서는 할 수 없는 요청(이미 방송 중인 라이브가 있음, 방송 중인 라이브 삭제 등). */
public class LiveConflictException extends RuntimeException {

    public LiveConflictException(String message) {
        super(message);
    }
}
