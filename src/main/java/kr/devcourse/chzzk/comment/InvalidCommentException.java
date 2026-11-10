package kr.devcourse.chzzk.comment;

/** 댓글 본문이 규칙(1~500자, 공백만 불가)을 어겼다. 메시지는 사용자에게 그대로 보여 줄 수 있다. */
public class InvalidCommentException extends RuntimeException {

    public InvalidCommentException(String message) {
        super(message);
    }
}
