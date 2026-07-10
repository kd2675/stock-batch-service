package stock.batch.service.batch.corporateaction.reader;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
            List<String> actionTypes
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
                """
        )
                .param("actionTypes", actionTypes)
                .param("announcedStatus", announcedStatus)
                .param("today", today)
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

    public List<Long> findDueCashDividendActionIds(LocalDate today, String actionType, String sourceStatus) {
        return jdbcClient.sql(
                """
                select id
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :sourceStatus
                   and payment_date <= :today
                 order by payment_date asc, id asc
                """
        )
                .param("actionType", actionType)
                .param("sourceStatus", sourceStatus)
                .param("today", today)
                .query((rs, rowNum) -> rs.getLong("id"))
                .list();
    }

    public List<CapitalIncreaseSubscriptionActionRow> findOpenCapitalIncreaseSubscriptions(
            LocalDate today,
            String actionType,
            List<String> statuses
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
                 order by subscription_end_date asc, id asc
                """
        )
                .param("actionType", actionType)
                .param("statuses", statuses)
                .param("today", today)
                .query((rs, rowNum) -> new CapitalIncreaseSubscriptionActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getString("offering_type"),
                        rs.getLong("share_quantity"),
                        rs.getBigDecimal("issue_price")
                ))
                .list();
    }

    public Optional<CapitalIncreaseSubscriptionActionRow> findPublicOfferingSubscriptionForUpdate(
            long actionId,
            String actionType,
            String status
    ) {
        return jdbcClient.sql(
                """
                select id, symbol, offering_type, share_quantity, issue_price
                  from stock_corporate_action
                 where id = :actionId
                   and action_type = :actionType
                   and status = :status
                   and offering_type = 'PUBLIC_OFFERING'
                   and share_quantity > 0
                   and issue_price > 0
                 for update
                """
        )
                .param("actionId", actionId)
                .param("actionType", actionType)
                .param("status", status)
                .query((rs, rowNum) -> new CapitalIncreaseSubscriptionActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getString("offering_type"),
                        rs.getLong("share_quantity"),
                        rs.getBigDecimal("issue_price")
                ))
                .optional();
    }

    public List<AutoParticipantCapitalIncreaseCandidate> findShareholderAllocationAutoCandidates(
            long actionId,
            String entitlementStatus
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
                 order by p.profile_type asc, a.id asc
                """
        )
                .param("actionId", actionId)
                .param("entitlementStatus", entitlementStatus)
                .query((rs, rowNum) -> new AutoParticipantCapitalIncreaseCandidate(
                        rs.getLong("entitlement_id"),
                        rs.getLong("account_id"),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getBigDecimal("cash_balance"),
                        rs.getLong("available_share_quantity")
                ))
                .list();
    }

    public List<AutoParticipantCapitalIncreaseCandidate> findPublicOfferingAutoCandidates(long actionId) {
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
                   and not exists (
                       select 1
                         from stock_corporate_action_entitlement e
                        where e.action_id = :actionId
                          and e.account_id = a.id
                   )
                 order by p.profile_type asc, a.id asc
                """
        )
                .param("actionId", actionId)
                .query((rs, rowNum) -> new AutoParticipantCapitalIncreaseCandidate(
                        null,
                        rs.getLong("account_id"),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getBigDecimal("cash_balance"),
                        rs.getLong("available_share_quantity")
                ))
                .list();
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
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getBigDecimal("shareholder_subscription_rate"),
                        rs.getBigDecimal("public_offering_subscription_rate"),
                        rs.getBigDecimal("max_cash_allocation_rate")
                ))
                .list();
    }

    public List<DividendEntitlementRow> findAnnouncedDividendEntitlements(long actionId, String status) {
        return jdbcClient.sql(
                """
                select id, account_id, cash_amount
                  from stock_corporate_action_entitlement
                 where action_id = :actionId
                   and status = :status
                 order by account_id asc, id asc
                """
        )
                .param("actionId", actionId)
                .param("status", status)
                .query((rs, rowNum) -> new DividendEntitlementRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getBigDecimal("cash_amount")
                ))
                .list();
    }

    public List<ListingActionRow> findDueListings(LocalDate today, String actionType, String sourceStatus) {
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
                """
        )
                .param("actionType", actionType)
                .param("sourceStatus", sourceStatus)
                .param("today", today)
                .query((rs, rowNum) -> new ListingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getLong("share_quantity")
                ))
                .list();
    }

    public List<StockSplitActionRow> findDueStockSplits(LocalDate today, String actionType, String status) {
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
                """
        )
                .param("actionType", actionType)
                .param("status", status)
                .param("today", today)
                .query((rs, rowNum) -> new StockSplitActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getInt("split_from"),
                        rs.getInt("split_to")
                ))
                .list();
    }

    public List<DelistingActionRow> findDueDelistings(LocalDate today, String actionType, String status) {
        return jdbcClient.sql(
                """
                select id, symbol
                  from stock_corporate_action
                 where action_type = :actionType
                   and status = :status
                   and delisting_date <= :today
                   and delisting_treatment = 'ZERO_VALUE'
                 order by delisting_date asc, id asc
                """
        )
                .param("actionType", actionType)
                .param("status", status)
                .param("today", today)
                .query((rs, rowNum) -> new DelistingActionRow(
                        rs.getLong("id"),
                        rs.getString("symbol")
                ))
                .list();
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
                """
        )
                .param("businessDate", businessDate)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    public List<ShareEntitlementRow> findAnnouncedShareEntitlements(long actionId, String status) {
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
                 order by account_id asc, symbol asc, id asc
                """
        )
                .param("actionId", actionId)
                .param("status", status)
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
