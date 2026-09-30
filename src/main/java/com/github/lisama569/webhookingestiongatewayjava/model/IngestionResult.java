package com.github.lisama569.webhookingestiongatewayjava.model;

import com.github.lisama569.webhookingestiongatewayjava.domain.Disposition;
import com.github.lisama569.webhookingestiongatewayjava.domain.EventStatus;
import com.github.lisama569.webhookingestiongatewayjava.domain.RejectionReason;

/**
 * 一次投递的处理结论（对外可解释）。
 */
public record IngestionResult(
        Disposition disposition,
        EventStatus status,
        RejectionReason rejectionReason,
        String partnerId,
        String eventId,
        Long currentVersion,
        String deadLetterId,
        String reason) {

    public static IngestionResult rejected(RejectionReason r, String partnerId, String eventId, String reason) {
        return new IngestionResult(Disposition.REJECTED, null, r, partnerId, eventId, null, null, reason);
    }
}
