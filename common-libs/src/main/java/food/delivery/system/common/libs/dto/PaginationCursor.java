package food.delivery.system.common.libs.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaginationCursor(
        @JsonProperty("cursor") String cursor,
        @JsonProperty("limit") int limit,
        @JsonProperty("hasMore") boolean hasMore
) {}
