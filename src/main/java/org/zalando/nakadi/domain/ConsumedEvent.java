package org.zalando.nakadi.domain;

import java.util.Objects;
import javax.annotation.concurrent.Immutable;

@Immutable
public class ConsumedEvent {

    private final String event;
    private final TopicPosition nextPosition;

    public ConsumedEvent(final String event, final TopicPosition nextPosition) {
        this.event = event;
        this.nextPosition = nextPosition;
    }

    public String getEvent() {
        return event;
    }

    public TopicPosition getNextPosition() {
        return nextPosition;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConsumedEvent)) {
            return false;
        }

        final ConsumedEvent that = (ConsumedEvent) o;
        return Objects.equals(this.event, that.event)
                && Objects.equals(this.nextPosition, that.nextPosition);
    }

    @Override
    public int hashCode() {
        return nextPosition.hashCode();
    }
}
