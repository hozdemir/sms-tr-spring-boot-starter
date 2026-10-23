package io.github.hozdemir.smstr.spi;

import java.util.List;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.MessageId;

/** Capability: the provider can be asked for the delivery state of a message it accepted. */
public interface DeliveryReportQuery {

    /** One report per recipient the provider knows about; empty while the provider has none yet. */
    List<DeliveryReport> fetchReports(MessageId messageId);
}
