package stock.batch.service.batch.corporateaction.reader;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.corporateaction.model.AutoParticipantCapitalIncreaseCandidate;
import stock.batch.service.batch.corporateaction.model.AutoParticipantEventProfilePolicy;
import stock.batch.service.batch.corporateaction.model.CapitalIncreaseSubscriptionActionRow;
import stock.batch.service.batch.corporateaction.model.DividendEntitlementRow;
import stock.batch.service.batch.corporateaction.model.DelistingActionRow;
import stock.batch.service.batch.corporateaction.model.ExRightsActionRow;
import stock.batch.service.batch.corporateaction.model.ExRightsPriceSnapshot;
import stock.batch.service.batch.corporateaction.model.ListingActionRow;
import stock.batch.service.batch.corporateaction.model.ShareEntitlementRow;
import stock.batch.service.batch.corporateaction.model.StockSplitActionRow;

@Component
public class CorporateActionReader {

    private final JdbcClient jdbcClient;

    public CorporateActionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    public List<ExRightsActionRow> findDueExRights(
            LocalDate today,
            String announcedStatus,
            List<String> actionTypes,
            int limit
    ) {
        return jdbcClient.sql(
                """
	                select id, symbol, action_type, offering_type, coalesce(share_quantity, 0) as share_quantity,
	                       issue_price, ex_rights_date, theoretical_ex_rights_price, dividend_amount
	                  from stock_corporate_action
	                 where action_type in (:actionTypes)
	                   and status = :announcedStatus
	                   and ex_rights_date <= :today
	                   and theoretical_ex_rights_price is not null
	                   and (action_type <> 'PAID_IN_CAPITAL_INCREASE' or offering_type = 'SHAREHOLDER_ALLOCATION')
	                 order by ex_rights_date asc, id asc
	                 limit :limit
                """
        )
                .param("actionTypes", actionTypes)
                .param("announcedStatus", announcedStatus)
                .param("today", today)
                .param("limit", limit)
                .query((rs, rowNum) -> new ExRightsActionRow(
	                        rs.getLong("id"),
	                        rs.getString("symbol"),
	                        rs.getString("action_type"),
	                        rs.getString("offering_type"),
	                        rs.getLong("share_quantity"),
	                        rs.getBigDecimal("issue_price"),
	                        rs.getObject("ex_rights_date", LocalDate.class),
                        rs.getBigDecimal("theoretical_ex_rights_price"),
                        rs.getBigDecimal("dividend_amount")
                ))
                .list();
    }

    public List<Long> findDueCashDividendActionIds(
            LocalDate today,
            String actionType,
            String sourceStatus,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select id
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :sourceStatus
                   and payment_date <= :today
                 order by payment_date asc, id asc
                 limit :limit
                """
        )
                .param("actionType", actionType)
                .param("sourceStatus", sourceStatus)
                .param("today", today)
                .param("limit", limit)
                .query((rs, rowNum) -> rs.getLong("id"))
                .list();
    }

    public List<CapitalIncreaseSubscriptionActionRow> findOpenCapitalIncreaseSubscriptions(
            LocalDate today,
            String actionType,
            List<String> statuses,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select id, symbol, offering_type, share_quantity, issue_price
                  from stock_corporate_action
                 where action_type = :actionType
                   and status in (:statuses)
                   and offering_type in ('SHAREHOLDER_ALLOCATION', 'PUBLIC_OFFERING')
                   and subscription_start_date <= :today
                   and subscription_end_date >= :today
                   and share_quantity > 0
                   and issue_price > 0
                   and not exists (
                       select 1
                         from stock_corporate_action_processing processing
                        where processing.action_id = stock_corporate_action.id
                          and processing.account_scope_key = 'ALL'
                          and processing.action_phase = case
                              when stock_corporate_action.offering_type = 'SHAREHOLDER_ALLOCATION'
                              then 'shareholder-allocation-auto-subscription'
                              else 'public-offering-auto-subscription'
                          end
                          and processing.effective_business_date = :today
                          and processing.status = 'COMPLETED'
                   )
                 order by subscription_end_date asc, id asc
                 limit :limit
                """
        )
                .param("actionType", actionType)
                .param("statuses", statuses)
                .param("today", today)
                .param("limit", limit)
                .query((rs, rowNum) -> new CapitalIncreaseSubscriptionActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getString("offering_type"),
                        rs.getLong("share_quantity"),
                        rs.getBigDecimal("issue_price")
                ))
                .list();
    }

    public long countIncompleteCorporateCashActions(LocalDate effectiveBusinessDate) {
        Long count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_corporate_action a
                         where (
                               a.action_type = 'CASH_DIVIDEND'
                               and a.status = 'EX_RIGHTS_APPLIED'
                               and a.payment_date <= :effectiveBusinessDate
                         ) or (
                               a.action_type = 'PAID_IN_CAPITAL_INCREASE'
                               and a.payment_date <= :effectiveBusinessDate
                               and (
                                   (a.offering_type = 'SHAREHOLDER_ALLOCATION' and a.status = 'EX_RIGHTS_APPLIED')
                                   or (a.offering_type = 'PUBLIC_OFFERING' and a.status = 'ANNOUNCED')
                               )
                         ) or (
                               a.action_type = 'PAID_IN_CAPITAL_INCREASE'
                               and a.subscription_start_date <= :effectiveBusinessDate
                               and a.subscription_end_date >= :effectiveBusinessDate
                               and (
                                   (a.offering_type = 'SHAREHOLDER_ALLOCATION' and a.status = 'EX_RIGHTS_APPLIED')
                                   or (a.offering_type = 'PUBLIC_OFFERING' and a.status = 'ANNOUNCED')
                               )
                               and not exists (
                                   select 1
                                     from stock_corporate_action_processing p
                                    where p.action_id = a.id
                                      and p.account_scope_key = 'ALL'
                                      and p.action_phase = case
                                          when a.offering_type = 'SHAREHOLDER_ALLOCATION'
                                          then 'shareholder-allocation-auto-subscription'
                                          else 'public-offering-auto-subscription'
                                      end
                                      and p.effective_business_date = :effectiveBusinessDate
                                      and p.status = 'COMPLETED'
                               )
                         )
                        """
                )
                .param("effectiveBusinessDate", effectiveBusinessDate)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public long countIncompletePreOpenSecurityTransforms(LocalDate preparingBusinessDate) {
        Long count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_corporate_action a
                         where (
                               a.status = 'ANNOUNCED'
                               and a.action_type in (
                                   'PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND',
                                   'BONUS_ISSUE', 'STOCK_DIVIDEND'
                               )
                               and a.ex_rights_date <= :preparingBusinessDate
                               and a.theoretical_ex_rights_price is not null
                               and (
                                   a.action_type <> 'PAID_IN_CAPITAL_INCREASE'
                                   or a.offering_type = 'SHAREHOLDER_ALLOCATION'
                               )
                         ) or (
                               a.status = 'PAID'
                               and a.action_type in (
                                   'PAID_IN_CAPITAL_INCREASE', 'BONUS_ISSUE', 'STOCK_DIVIDEND'
                               )
                               and a.listing_date <= :preparingBusinessDate
                         ) or (
                               a.status = 'EX_RIGHTS_APPLIED'
                               and a.action_type in ('BONUS_ISSUE', 'STOCK_DIVIDEND')
                               and a.listing_date <= :preparingBusinessDate
                         ) or (
                               a.status = 'ANNOUNCED'
                               and a.action_type = 'STOCK_SPLIT'
                               and a.listing_date <= :preparingBusinessDate
                         ) or (
                               a.status = 'ANNOUNCED'
                               and a.action_type = 'DELISTING'
                               and a.delisting_date <= :preparingBusinessDate
                               and a.delisting_treatment = 'ZERO_VALUE'
                         )
                        """
                )
                .param("preparingBusinessDate", preparingBusinessDate)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public Optional<CapitalIncreaseSubscriptionActionRow> findCapitalIncreaseSubscriptionForUpdate(
            long actionId,
            LocalDate today,
            String actionType,
            String status,
            String offeringType
    ) {
        return jdbcClient.sql(
                """
                select id, symbol, offering_type, share_quantity, issue_price
                  from stock_corporate_action
                 where id = :actionId
                   and action_type = :actionType
                   and status = :status
                   and offering_type = :offeringType
                   and subscription_start_date <= :today
                   and subscription_end_date >= :today
                   and share_quantity > 0
                   and issue_price > 0
                 for update
                """
        )
                .param("actionId", actionId)
                .param("today", today)
                .param("actionType", actionType)
                .param("status", status)
                .param("offeringType", offeringType)
                .query((rs, rowNum) -> new CapitalIncreaseSubscriptionActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getString("offering_type"),
                        rs.getLong("share_quantity"),
                        rs.getBigDecimal("issue_price")
                ))
                .optional();
    }

    public List<AutoParticipantCapitalIncreaseCandidate> findShareholderAllocationAutoCandidateChunk(
            long actionId,
            String entitlementStatus,
            String actionPhase,
            LocalDate effectiveBusinessDate,
            long afterAccountId,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select e.id as entitlement_id,
                       a.id as account_id,
                       p.profile_type,
                       a.cash_balance,
                       e.share_quantity as available_share_quantity
                  from stock_corporate_action_entitlement e
                  join stock_account a
                    on a.id = e.account_id
                   and a.status = 'ACTIVE'
                  join stock_auto_participant p
                    on p.user_key = a.user_key
                   and p.enabled = true
                   and p.withdrawn_at is null
                 where e.action_id = :actionId
                   and e.status = :entitlementStatus
                   and e.share_quantity > 0
                   and a.id > :afterAccountId
                   and not exists (
                       select 1
                         from stock_corporate_action_processing cap
                        where cap.action_id = e.action_id
                          and cap.account_scope_key = concat('A:', a.id)
                          and cap.action_phase = :actionPhase
                          and cap.effective_business_date = :effectiveBusinessDate
                          and cap.status = 'COMPLETED'
                   )
                 order by a.id asc, e.id asc
                 limit :limit
                """
        )
                .param("actionId", actionId)
                .param("entitlementStatus", entitlementStatus)
                .param("actionPhase", actionPhase)
                .param("effectiveBusinessDate", effectiveBusinessDate)
                .param("afterAccountId", afterAccountId)
                .param("limit", limit)
                .query((rs, rowNum) -> new AutoParticipantCapitalIncreaseCandidate(
                        rs.getLong("entitlement_id"),
                        rs.getLong("account_id"),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getBigDecimal("cash_balance"),
                        rs.getLong("available_share_quantity")
                ))
                .list();
    }

    public List<AutoParticipantCapitalIncreaseCandidate> findPublicOfferingAutoCandidateChunk(
            long actionId,
            String actionPhase,
            LocalDate effectiveBusinessDate,
            long afterAccountId,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select null as entitlement_id,
                       a.id as account_id,
                       p.profile_type,
                       a.cash_balance,
                       9223372036854775807 as available_share_quantity
                  from stock_auto_participant p
                  join stock_account a
                    on a.user_key = p.user_key
                   and a.status = 'ACTIVE'
                 where p.enabled = true
                   and p.withdrawn_at is null
                   and a.id > :afterAccountId
                   and not exists (
                       select 1
                         from stock_corporate_action_entitlement e
                        where e.action_id = :actionId
                          and e.account_id = a.id
                   )
                   and not exists (
                       select 1
                         from stock_corporate_action_processing cap
                        where cap.action_id = :actionId
                          and cap.account_scope_key = concat('A:', a.id)
                          and cap.action_phase = :actionPhase
                          and cap.effective_business_date = :effectiveBusinessDate
                          and cap.status = 'COMPLETED'
                   )
                 order by a.id asc
                 limit :limit
                """
        )
                .param("actionId", actionId)
                .param("actionPhase", actionPhase)
                .param("effectiveBusinessDate", effectiveBusinessDate)
                .param("afterAccountId", afterAccountId)
                .param("limit", limit)
                .query((rs, rowNum) -> new AutoParticipantCapitalIncreaseCandidate(
                        null,
                        rs.getLong("account_id"),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getBigDecimal("cash_balance"),
                        rs.getLong("available_share_quantity")
                ))
                .list();
    }

    public Map<Long, BigDecimal> findActiveAccountCashForUpdate(List<Long> accountIds) {
        List<Long> sortedAccountIds = accountIds.stream().distinct().sorted().toList();
        if (sortedAccountIds.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<Long, BigDecimal>> rows = jdbcClient.sql(
                """
                select id, cash_balance
                  from stock_account
                 where id in (:accountIds)
                   and status = 'ACTIVE'
                 order by id asc
                 for update
                """
        )
                .param("accountIds", sortedAccountIds)
                .query((rs, rowNum) -> Map.entry(
                        rs.getLong("id"),
                        rs.getBigDecimal("cash_balance")
                ))
                .list();
        Map<Long, BigDecimal> cashByAccountId = new LinkedHashMap<>();
        rows.forEach(row -> cashByAccountId.put(row.getKey(), row.getValue()));
        return cashByAccountId;
    }

    public Map<Long, Long> findAnnouncedEntitlementShareQuantityForUpdate(
            List<Long> entitlementIds,
            long actionId,
            String status
    ) {
        List<Long> sortedEntitlementIds = entitlementIds.stream().distinct().sorted().toList();
        if (sortedEntitlementIds.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<Long, Long>> rows = jdbcClient.sql(
                """
                select id, share_quantity
                  from stock_corporate_action_entitlement
                 where id in (:entitlementIds)
                   and action_id = :actionId
                   and status = :status
                   and share_quantity > 0
                 order by id asc
                 for update
                """
        )
                .param("entitlementIds", sortedEntitlementIds)
                .param("actionId", actionId)
                .param("status", status)
                .query((rs, rowNum) -> Map.entry(
                        rs.getLong("id"),
                        rs.getLong("share_quantity")
                ))
                .list();
        Map<Long, Long> sharesByEntitlementId = new LinkedHashMap<>();
        rows.forEach(row -> sharesByEntitlementId.put(row.getKey(), row.getValue()));
        return sharesByEntitlementId;
    }

    public List<CapitalIncreaseSubscriptionActionRow> findDueCapitalIncreasePayments(
            LocalDate today,
            String actionType,
            String shareholderStatus,
            String publicOfferingStatus,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select id, symbol, offering_type, share_quantity, issue_price
                  from stock_corporate_action
                 where action_type = :actionType
                   and payment_date <= :today
                   and (
                       (offering_type = 'SHAREHOLDER_ALLOCATION' and status = :shareholderStatus)
                       or (offering_type = 'PUBLIC_OFFERING' and status = :publicOfferingStatus)
                   )
                 order by payment_date asc, id asc
                 limit :limit
                """
        )
                .param("today", today)
                .param("actionType", actionType)
                .param("shareholderStatus", shareholderStatus)
                .param("publicOfferingStatus", publicOfferingStatus)
                .param("limit", limit)
                .query((rs, rowNum) -> new CapitalIncreaseSubscriptionActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getString("offering_type"),
                        rs.getLong("share_quantity"),
                        rs.getBigDecimal("issue_price")
                ))
                .list();
    }

    public Optional<CapitalIncreaseSubscriptionActionRow> findDueCapitalIncreasePaymentForUpdate(
            long actionId,
            LocalDate today,
            String actionType,
            String status,
            String offeringType
    ) {
        return jdbcClient.sql(
                """
                select id, symbol, offering_type, share_quantity, issue_price
                  from stock_corporate_action
                 where id = :actionId
                   and action_type = :actionType
                   and status = :status
                   and offering_type = :offeringType
                   and subscription_end_date < payment_date
                   and payment_date <= :today
                 for update
                """
        )
                .param("actionId", actionId)
                .param("today", today)
                .param("actionType", actionType)
                .param("status", status)
                .param("offeringType", offeringType)
                .query((rs, rowNum) -> new CapitalIncreaseSubscriptionActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getString("offering_type"),
                        rs.getLong("share_quantity"),
                        rs.getBigDecimal("issue_price")
                ))
                .optional();
    }

    public long sumSubscribedShareQuantity(long actionId) {
        Long quantity = jdbcClient.sql(
                """
                select coalesce(sum(subscribed_share_quantity), 0)
                  from stock_corporate_action_entitlement
                 where action_id = :actionId
                   and status in ('SUBSCRIBED', 'PAID')
                """
        )
                .param("actionId", actionId)
                .query(Long.class)
                .single();
        return quantity == null ? 0L : quantity;
    }

    public List<AutoParticipantEventProfilePolicy> findEventProfilePolicies() {
        return jdbcClient.sql(
                """
                select profile_type,
                       shareholder_subscription_rate,
                       public_offering_subscription_rate,
                       max_cash_allocation_rate
                  from stock_auto_participant_event_profile_config
                 order by profile_type asc
                """
        )
                .query((rs, rowNum) -> new AutoParticipantEventProfilePolicy(
                        requireEventProfileType(rs.getString("profile_type")),
                        rs.getBigDecimal("shareholder_subscription_rate"),
                        rs.getBigDecimal("public_offering_subscription_rate"),
                        rs.getBigDecimal("max_cash_allocation_rate")
                ))
                .list();
    }

    private AutoParticipantProfileType requireEventProfileType(String profileType) {
        try {
            return AutoParticipantProfileType.valueOf(profileType);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown auto participant event profile type: " + profileType, ex);
        }
    }

    public List<DividendEntitlementRow> findDividendEntitlementChunk(long actionId, String status, int limit) {
        return jdbcClient.sql(
                """
                select id, account_id, cash_amount
                  from stock_corporate_action_entitlement
                 where action_id = :actionId
                   and status = :status
                 order by id asc
                 limit :limit
                """
        )
                .param("actionId", actionId)
                .param("status", status)
                .param("limit", limit)
                .query((rs, rowNum) -> new DividendEntitlementRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getBigDecimal("cash_amount")
                ))
                .list();
    }

    public boolean existsEntitlementWithStatus(long actionId, String status) {
        return jdbcClient.sql(
                """
                select id
                  from stock_corporate_action_entitlement
                 where action_id = :actionId
                   and status = :status
                 order by id asc
                 limit 1
                """
        )
                .param("actionId", actionId)
                .param("status", status)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    public List<Long> findEntitlementIdChunk(long actionId, String status, int limit) {
        return jdbcClient.sql(
                """
                select id
                  from stock_corporate_action_entitlement
                 where action_id = :actionId
                   and status = :status
                 order by id asc
                 limit :limit
                """
        )
                .param("actionId", actionId)
                .param("status", status)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    public List<ListingActionRow> findDueListings(
            LocalDate today,
            String actionType,
            String sourceStatus,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select a.id,
                       a.symbol,
                       case
                           when a.action_type = 'PAID_IN_CAPITAL_INCREASE' then coalesce((
                               select sum(e.subscribed_share_quantity)
                                 from stock_corporate_action_entitlement e
                                where e.action_id = a.id
                                  and e.status in ('SUBSCRIBED', 'PAID')
                           ), 0)
                           else a.share_quantity
                       end as share_quantity
                  from stock_corporate_action a
                 where a.action_type = :actionType
                   and a.status = :sourceStatus
                   and a.listing_date <= :today
                   and (
                       a.action_type = 'PAID_IN_CAPITAL_INCREASE'
                       or a.share_quantity > 0
                   )
                 order by a.listing_date asc, a.id asc
                 limit :limit
                """
        )
                .param("actionType", actionType)
                .param("sourceStatus", sourceStatus)
                .param("today", today)
                .param("limit", limit)
                .query((rs, rowNum) -> new ListingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getLong("share_quantity")
                ))
                .list();
    }

    public Optional<ListingActionRow> findDueListingForUpdate(
            long actionId,
            LocalDate today,
            String actionType,
            String sourceStatus
    ) {
        return jdbcClient.sql(
                """
                select id, symbol, coalesce(share_quantity, 0) as share_quantity
                  from stock_corporate_action
                 where id = :actionId
                   and action_type = :actionType
                   and status = :sourceStatus
                   and listing_date <= :today
                 for update
                """
        )
                .param("actionId", actionId)
                .param("today", today)
                .param("actionType", actionType)
                .param("sourceStatus", sourceStatus)
                .query((rs, rowNum) -> new ListingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getLong("share_quantity")
                ))
                .optional();
    }

    public boolean lockDueActionForUpdate(
            long actionId,
            LocalDate today,
            String actionType,
            String status,
            String dueDateColumn
    ) {
        if (!Set.of("ex_rights_date", "payment_date", "listing_date", "delisting_date").contains(dueDateColumn)) {
            throw new IllegalArgumentException("Unsupported corporate action due date column: " + dueDateColumn);
        }
        return jdbcClient.sql(
                """
                select id
                  from stock_corporate_action
                 where id = :actionId
                   and action_type = :actionType
                   and status = :status
                   and %s <= :today
                 for update
                """.formatted(dueDateColumn)
        )
                .param("actionId", actionId)
                .param("today", today)
                .param("actionType", actionType)
                .param("status", status)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    public List<StockSplitActionRow> findDueStockSplits(
            LocalDate today,
            String actionType,
            String status,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select id, symbol, split_from, split_to
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :status
                   and listing_date <= :today
                   and split_from > 0
                   and split_to > split_from
                 order by listing_date asc, id asc
                 limit :limit
                """
        )
                .param("actionType", actionType)
                .param("status", status)
                .param("today", today)
                .param("limit", limit)
                .query((rs, rowNum) -> new StockSplitActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getInt("split_from"),
                        rs.getInt("split_to")
                ))
                .list();
    }

    public List<Long> findHoldingAccountChunkForSplit(
            long actionId,
            String symbol,
            String actionPhase,
            LocalDate effectiveBusinessDate,
            long afterAccountId,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select h.account_id
                  from stock_holding h
                 where h.symbol = :symbol
                   and h.account_id > :afterAccountId
                   and not exists (
                       select 1
                         from stock_corporate_action_processing cap
                        where cap.action_id = :actionId
                          and cap.account_scope_key = concat('A:', h.account_id)
                          and cap.action_phase = :actionPhase
                          and cap.effective_business_date = :effectiveBusinessDate
                          and cap.status = 'COMPLETED'
                   )
                 order by h.account_id asc
                 limit :limit
                """
        )
                .param("actionId", actionId)
                .param("symbol", symbol)
                .param("actionPhase", actionPhase)
                .param("effectiveBusinessDate", effectiveBusinessDate)
                .param("afterAccountId", afterAccountId)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    public List<DelistingActionRow> findDueDelistings(
            LocalDate today,
            String actionType,
            String status,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select id, symbol
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :status
                   and delisting_date <= :today
                   and delisting_treatment = 'ZERO_VALUE'
                 order by delisting_date asc, id asc
                 limit :limit
                """
        )
                .param("actionType", actionType)
                .param("status", status)
                .param("today", today)
                .param("limit", limit)
                .query((rs, rowNum) -> new DelistingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol")
                ))
                .list();
    }

    public Optional<ExRightsPriceSnapshot> findExRightsPriceSnapshot(long closeRunId, String symbol) {
        return jdbcClient.sql(
                """
                select close_price, issued_shares
                  from stock_order_book_daily_snapshot
                 where close_run_id = :closeRunId
                   and symbol = :symbol
                   and close_price > 0
                   and issued_shares > 0
                """
        )
                .param("closeRunId", closeRunId)
                .param("symbol", symbol)
                .query((rs, rowNum) -> new ExRightsPriceSnapshot(
                        rs.getBigDecimal("close_price"),
                        rs.getLong("issued_shares")
                ))
                .optional();
    }

    public Optional<Long> findLatestCompletedMarketCloseRunIdBefore(String symbol, LocalDate actionDate) {
        return jdbcClient.sql(
                """
                select id
                  from stock_market_close_run
                 where status = 'COMPLETED'
                   and business_date < :actionDate
                   and (symbol = :symbol or symbol is null)
                 order by business_date desc, closed_at desc, id desc
                 limit 1
                """
        )
                .param("symbol", symbol)
                .param("actionDate", actionDate)
                .query(Long.class)
                .optional();
    }

    public boolean existsCompletedMarketCloseRun(LocalDate businessDate) {
        Long count = jdbcClient.sql(
                """
                select count(*)
                  from stock_market_close_run
                 where status = 'COMPLETED'
                   and business_date = :businessDate
                   and symbol is null
                """
        )
                .param("businessDate", businessDate)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    public List<ShareEntitlementRow> findShareEntitlementChunk(long actionId, String status, int limit) {
        return jdbcClient.sql(
                """
                select id,
                       account_id,
                       symbol,
                       coalesce(subscribed_share_quantity, share_quantity) as share_quantity,
                       subscribed_cash_amount
                  from stock_corporate_action_entitlement
                 where action_id = :actionId
                   and status = :status
                   and coalesce(subscribed_share_quantity, share_quantity) > 0
                 order by id asc
                 limit :limit
                """
        )
                .param("actionId", actionId)
                .param("status", status)
                .param("limit", limit)
                .query((rs, rowNum) -> new ShareEntitlementRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getLong("share_quantity"),
                        rs.getBigDecimal("subscribed_cash_amount")
                ))
                .list();
    }
}
