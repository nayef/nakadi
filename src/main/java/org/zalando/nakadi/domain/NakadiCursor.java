package org.zalando.nakadi.domain;

public class NakadiCursor {
    private final String evenType;
    private final String partition;
    private final String position;

    public NakadiCursor(final String evenType, final String partition, final String position) {
        this.evenType = evenType;
        this.partition = partition;
        this.position = position;
    }

    public String getEvenType() {
        return evenType;
    }

    public String getPartition() {
        return partition;
    }

    public String getPosition() {
        return position;
    }
}
