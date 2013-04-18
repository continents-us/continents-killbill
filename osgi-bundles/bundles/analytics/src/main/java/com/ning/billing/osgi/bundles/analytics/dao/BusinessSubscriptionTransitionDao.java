/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.osgi.bundles.analytics.dao;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.skife.jdbi.v2.Transaction;
import org.skife.jdbi.v2.TransactionStatus;

import com.ning.billing.account.api.Account;
import com.ning.billing.entitlement.api.user.Subscription;
import com.ning.billing.entitlement.api.user.SubscriptionBundle;
import com.ning.billing.entitlement.api.user.SubscriptionTransition;
import com.ning.billing.osgi.bundles.analytics.AnalyticsRefreshException;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessAccountModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessBundleSummaryModelDao;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessModelDaoBase.ReportGroup;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessSubscription;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessSubscriptionEvent;
import com.ning.billing.osgi.bundles.analytics.dao.model.BusinessSubscriptionTransitionModelDao;
import com.ning.billing.util.audit.AuditLog;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillAPI;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillDataSource;
import com.ning.killbill.osgi.libs.killbill.OSGIKillbillLogService;

public class BusinessSubscriptionTransitionDao extends BusinessAnalyticsDaoBase {

    private final BusinessAccountDao businessAccountDao;
    private final BusinessBundleSummaryDao businessBundleSummaryDao;

    public BusinessSubscriptionTransitionDao(final OSGIKillbillLogService logService,
                                             final OSGIKillbillAPI osgiKillbillAPI,
                                             final OSGIKillbillDataSource osgiKillbillDataSource,
                                             final BusinessAccountDao businessAccountDao) {
        super(logService, osgiKillbillAPI, osgiKillbillDataSource);
        this.businessAccountDao = businessAccountDao;
        this.businessBundleSummaryDao = new BusinessBundleSummaryDao(logService, osgiKillbillAPI, osgiKillbillDataSource);
    }

    public void update(final UUID accountId, final CallContext context) throws AnalyticsRefreshException {
        final Account account = getAccount(accountId, context);
        final ReportGroup reportGroup = getReportGroup(account.getId(), context);

        // Recompute the account record
        final BusinessAccountModelDao bac = businessAccountDao.createBusinessAccount(account, context);

        // Recompute all invoices and invoice items
        final Collection<BusinessSubscriptionTransitionModelDao> bsts = createBusinessSubscriptionTransitions(account,
                                                                                                              bac.getAccountRecordId(),
                                                                                                              bac.getTenantRecordId(),
                                                                                                              reportGroup,
                                                                                                              context);

        // Recompute the bundle summary records
        final Collection<BusinessBundleSummaryModelDao> bbss = businessBundleSummaryDao.createBusinessBundleSummaries(account,
                                                                                                                      bac.getAccountRecordId(),
                                                                                                                      bsts,
                                                                                                                      bac.getTenantRecordId(),
                                                                                                                      reportGroup,
                                                                                                                      context);
        sqlDao.inTransaction(new Transaction<Void, BusinessAnalyticsSqlDao>() {
            @Override
            public Void inTransaction(final BusinessAnalyticsSqlDao transactional, final TransactionStatus status) throws Exception {
                updateInTransaction(bac, bbss, bsts, transactional, context);
                return null;
            }
        });
    }

    public void updateInTransaction(final BusinessAccountModelDao bac,
                                    final Collection<BusinessBundleSummaryModelDao> bbss,
                                    final Collection<BusinessSubscriptionTransitionModelDao> bsts,
                                    final BusinessAnalyticsSqlDao transactional,
                                    final CallContext context) {
        // Update the subscription transitions
        transactional.deleteByAccountRecordId(BusinessSubscriptionTransitionModelDao.SUBSCRIPTION_TABLE_NAME,
                                              bac.getAccountRecordId(),
                                              bac.getTenantRecordId(),
                                              context);

        for (final BusinessSubscriptionTransitionModelDao bst : bsts) {
            transactional.create(bst.getTableName(), bst, context);
        }

        // Update the summary table per bundle
        businessBundleSummaryDao.updateInTransaction(bbss,
                                                     bac.getAccountRecordId(),
                                                     bac.getTenantRecordId(),
                                                     transactional,
                                                     context);

        // Update BAC
        businessAccountDao.updateInTransaction(bac, transactional, context);
    }

    private Collection<BusinessSubscriptionTransitionModelDao> createBusinessSubscriptionTransitions(final Account account,
                                                                                                     final Long accountRecordId,
                                                                                                     final Long tenantRecordId,
                                                                                                     @Nullable final ReportGroup reportGroup,
                                                                                                     final CallContext context) throws AnalyticsRefreshException {
        final Collection<BusinessSubscriptionTransitionModelDao> bsts = new LinkedList<BusinessSubscriptionTransitionModelDao>();

        final List<SubscriptionBundle> bundles = getSubscriptionBundlesForAccount(account.getId(), context);
        for (final SubscriptionBundle bundle : bundles) {
            final Collection<Subscription> subscriptions = getSubscriptionsForBundle(bundle.getId(), context);
            for (final Subscription subscription : subscriptions) {
                final List<SubscriptionTransition> transitions = subscription.getAllTransitions();

                BusinessSubscription prevNextSubscription = null;

                // Ordered for us by entitlement
                for (final SubscriptionTransition transition : transitions) {
                    final BusinessSubscription nextSubscription = getBusinessSubscriptionFromTransition(account, transition);
                    final BusinessSubscriptionTransitionModelDao bst = createBusinessSubscriptionTransition(account,
                                                                                                            accountRecordId,
                                                                                                            bundle,
                                                                                                            transition,
                                                                                                            prevNextSubscription,
                                                                                                            nextSubscription,
                                                                                                            tenantRecordId,
                                                                                                            reportGroup,
                                                                                                            context);
                    if (bst != null) {
                        bsts.add(bst);
                        prevNextSubscription = nextSubscription;
                    }
                }
            }
        }

        return bsts;
    }

    private BusinessSubscriptionTransitionModelDao createBusinessSubscriptionTransition(final Account account,
                                                                                        final Long accountRecordId,
                                                                                        final SubscriptionBundle subscriptionBundle,
                                                                                        final SubscriptionTransition subscriptionTransition,
                                                                                        @Nullable final BusinessSubscription prevNextSubscription,
                                                                                        final BusinessSubscription nextSubscription,
                                                                                        final Long tenantRecordId,
                                                                                        @Nullable final ReportGroup reportGroup,
                                                                                        final CallContext context) throws AnalyticsRefreshException {
        final BusinessSubscriptionEvent businessEvent = BusinessSubscriptionEvent.fromTransition(subscriptionTransition);
        if (businessEvent == null) {
            return null;
        }

        final Long subscriptionEventRecordId = getSubscriptionEventRecordId(subscriptionTransition.getNextEventId(), context);
        final AuditLog creationAuditLog = getSubscriptionEventCreationAuditLog(subscriptionTransition.getNextEventId(), context);

        return new BusinessSubscriptionTransitionModelDao(account,
                                                          accountRecordId,
                                                          subscriptionBundle,
                                                          subscriptionTransition,
                                                          subscriptionEventRecordId,
                                                          subscriptionTransition.getRequestedTransitionTime(),
                                                          businessEvent,
                                                          prevNextSubscription,
                                                          nextSubscription,
                                                          creationAuditLog,
                                                          tenantRecordId,
                                                          reportGroup);
    }

    private BusinessSubscription getBusinessSubscriptionFromTransition(final Account account, final SubscriptionTransition subscriptionTransition) {
        return new BusinessSubscription(subscriptionTransition.getNextPlan(),
                                        subscriptionTransition.getNextPhase(),
                                        subscriptionTransition.getNextPriceList(),
                                        account.getCurrency(),
                                        subscriptionTransition.getEffectiveTransitionTime(),
                                        subscriptionTransition.getNextState());
    }
}
