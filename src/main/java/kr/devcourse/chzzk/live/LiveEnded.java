package kr.devcourse.chzzk.live;

/** 라이브 방송이 방송 중에서 종료로 바뀌었다(방송자 종료·연결 끊김). 시그널링이 시청자에게 알리는 데 쓴다. */
record LiveEnded(Long liveId) {
}
