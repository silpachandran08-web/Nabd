package com.nabd.hms.platform.fleet;

import com.nabd.hms.messaging.api.SenderDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** COM-022: the Comms Gateway's sender list with the clinic names Core knows. No transaction is open while the gateway is called. */
@Service
public class PlatformSenderService {

    private final SenderDirectory senders;
    private final FleetRepository repo;
    private final TransactionTemplate tx;

    PlatformSenderService(SenderDirectory senders, FleetRepository repo, PlatformTransactionManager tm) {
        this.tx = new TransactionTemplate(tm);
        this.senders = senders;
        this.repo = repo;
    }

    public SenderDirectory.FleetPage list(String rating, String region, String cursor, int limit) {
        SenderDirectory.FleetPage page = senders.fleet(rating, region, cursor, limit);
        List<UUID> ids = page.items().stream().map(SenderDirectory.FleetItem::tenantId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> names = tx.execute(s -> repo.tenantNames(ids)); // own short transaction: none is open while the gateway is called
        return new SenderDirectory.FleetPage(page.items().stream().map(i -> new SenderDirectory.FleetItem(i.senderAccountId(), i.tenantId(),
                i.tenantId() == null ? "Shared Nabd number" : names.get(i.tenantId()), i.kind(), i.displayPhone(), i.region(), i.status(), i.rating(), i.tier(),
                i.throttleMode(), i.deliveredRate7d(), i.sends7d())).toList(), page.nextCursor());
    }
}
