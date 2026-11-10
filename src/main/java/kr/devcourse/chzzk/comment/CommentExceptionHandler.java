package kr.devcourse.chzzk.comment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 댓글 화면 요청과 JSON 요청이 함께 쓰는 오류 응답: 없는 방송·댓글은 404, 작성자가 아니면 403. */
@ControllerAdvice(assignableTypes = {CommentController.class, CommentJsonController.class})
class CommentExceptionHandler {

    @ExceptionHandler({BroadcastNotFoundException.class, CommentNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void notFound() {
    }

    @ExceptionHandler(NotCommentAuthorException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    void forbidden() {
    }
}
