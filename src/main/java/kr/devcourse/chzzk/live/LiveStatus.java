package kr.devcourse.chzzk.live;

/** 방송 상태. 방송 중에서 종료로만 바뀐다. */
public enum LiveStatus {
    /** 시작되어 시청할 수 있다. */
    ON_AIR("방송 중"),
    /** 끝났다. 다시 방송 중이 될 수 없다. */
    ENDED("종료");

    private final String label;

    LiveStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
