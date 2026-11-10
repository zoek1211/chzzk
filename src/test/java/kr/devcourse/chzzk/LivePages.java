package kr.devcourse.chzzk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 시청 화면 HTML에서 종료 안내가 실제로 보이는 상태인지 읽는 테스트 도우미. */
final class LivePages {

    private static final Pattern ENDED_VIEW = Pattern.compile("<[^>]*id=\"ended-view\"[^>]*>");

    private LivePages() {
    }

    /** 종료 안내 영역이 있고 숨겨져 있지 않다. 방송 중 화면에는 숨겨진 채로만 있다. */
    static boolean showsEnded(String html) {
        Matcher tag = ENDED_VIEW.matcher(html);
        return tag.find() && !tag.group().contains("hidden");
    }
}
