package com.localbudget.app.domain.service;

import com.localbudget.app.converter.TransactionConverter;
import com.localbudget.app.data.repository.TransactionCsvRepository;
import com.localbudget.app.domain.model.AccountDO;
import com.localbudget.app.domain.model.PlaidItem;
import com.localbudget.app.domain.model.TransactionDO;
import com.localbudget.app.domain.model.TransactionView;
import com.localbudget.app.domain.model.command.TransactionQueryCommand;
import com.localbudget.app.domain.model.command.UpdateTransactionsCommand;
import com.localbudget.app.domain.model.result.TransactionMergeResult;
import com.localbudget.app.domain.service.helper.TransactionServiceHelper;
import com.localbudget.app.gateway.plaid.api.PlaidGateway;
import io.micrometer.common.util.StringUtils;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final PlaidGateway plaidGateway;
    private final TransactionCsvRepository transactionRepository;
    private final TransactionConverter transactionConverter;
    private final TransactionServiceHelper transactionServiceHelper;

    public List<TransactionDO> fetchTransactions(
            List<PlaidItem> plaidItems,
            Map<String, List<AccountDO>> trackedAccountsByPlaidItemId,
            LocalDate startDate,
            LocalDate endDate) {
        List<TransactionDO> transactions = new ArrayList<>();
        for (PlaidItem plaidItem : plaidItems) {
            List<AccountDO> trackedAccounts =
                    trackedAccountsByPlaidItemId.getOrDefault(plaidItem.plaidItemId(), List.of());
            plaidGateway.fetchTransactions(plaidItem, trackedAccounts, startDate, endDate).stream()
                    .map(
                            transaction ->
                                    transactionConverter.fromPlaid(
                                            plaidItem, trackedAccounts, transaction))
                    .forEach(transactions::add);
        }
        return transactions;
    }

    public List<TransactionDO> applyRules(List<TransactionDO> transactions) {
        return transactions.stream().map(transactionServiceHelper::markTransfersExcluded).toList();
    }

    public TransactionMergeResult mergeIntoLocalStore(List<TransactionDO> fetchedTransactions) {
        Map<String, TransactionDO> merged = new LinkedHashMap<>();
        for (TransactionDO existing : findAll()) {
            merged.put(existing.transactionId(), existing);
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        // Process pending entries first so a posted replacement wins regardless of input order.
        List<TransactionDO> ordered =
                fetchedTransactions.stream()
                        .sorted(Comparator.comparing(transaction -> !transaction.pending()))
                        .toList();
        for (TransactionDO fetched : ordered) {
            if (fetched.pending()
                    && merged.values().stream()
                            .anyMatch(posted -> replacesPending(posted, fetched))) {
                continue;
            }
            TransactionDO existing = merged.get(fetched.transactionId());
            TransactionDO pending = merged.get(fetched.pendingTransactionId());
            boolean replacedPending = replacesPending(fetched, pending);
            if (replacedPending) {
                merged.remove(pending.transactionId());
                if (existing == null) {
                    existing = pending;
                }
            }
            TransactionDO candidate =
                    transactionServiceHelper
                            .preserveLocalEdits(fetched, existing)
                            .withLocalCategoryIdIfUnassigned(
                                    transactionServiceHelper.defaultCategoryId(fetched));
            if (existing == null) {
                added++;
            } else if (replacedPending || !Objects.equals(existing, candidate)) {
                updated++;
            } else {
                unchanged++;
            }
            merged.put(candidate.transactionId(), candidate);
        }

        transactionRepository.writeAll(
                merged.values().stream().map(transactionConverter::toCsv).toList());
        return new TransactionMergeResult(added, updated, unchanged);
    }

    private static boolean replacesPending(TransactionDO posted, TransactionDO pending) {
        return pending != null
                && pending.pending()
                && !posted.pending()
                && StringUtils.isNotBlank(posted.pendingTransactionId())
                && posted.pendingTransactionId().equals(pending.transactionId())
                && !posted.transactionId().equals(pending.transactionId())
                && Objects.equals(posted.accountId(), pending.accountId())
                && Objects.equals(posted.plaidItemId(), pending.plaidItemId());
    }

    public List<TransactionView> find(
            TransactionQueryCommand command, Map<String, String> displayNamesById) {
        LocalDate start = resolveStart(command);
        LocalDate end = resolveEnd(command);
        return findAll().stream()
                .filter(transaction -> !transaction.effectiveDate().isBefore(start))
                .filter(transaction -> !transaction.effectiveDate().isAfter(end))
                .filter(
                        transaction ->
                                command.accountId() == null
                                        || command.accountId().isBlank()
                                        || command.accountId().equals(transaction.accountId()))
                .filter(
                        transaction ->
                                command.category() == null
                                        || command.category().isBlank()
                                        || command.category()
                                                .equalsIgnoreCase(
                                                        transactionServiceHelper.displayCategory(
                                                                transaction, displayNamesById)))
                .sorted(
                        Comparator.comparing(
                                        (TransactionDO transaction) -> transaction.effectiveDate())
                                .reversed()
                                .thenComparing(transaction -> transaction.transactionId()))
                .map(transaction -> toView(transaction, displayNamesById))
                .toList();
    }

    public List<TransactionDO> findAll() {
        return transactionRepository.findAll().stream().map(transactionConverter::fromCsv).toList();
    }

    public TransactionDO updateLocalCategory(String transactionId, String categoryId) {
        List<TransactionDO> transactions = findAll();
        boolean found =
                transactions.stream()
                        .anyMatch(transaction -> transaction.transactionId().equals(transactionId));
        if (!found) {
            throw new NoSuchElementException("Transaction not found: " + transactionId);
        }

        List<TransactionDO> updated =
                transactions.stream()
                        .map(
                                transaction ->
                                        transaction.transactionId().equals(transactionId)
                                                ? transaction.withLocalCategoryId(categoryId)
                                                : transaction)
                        .toList();
        transactionRepository.writeAll(updated.stream().map(transactionConverter::toCsv).toList());
        return updated.stream()
                .filter(transaction -> transaction.transactionId().equals(transactionId))
                .findFirst()
                .orElseThrow();
    }

    public List<TransactionDO> updateTransactions(UpdateTransactionsCommand command) {
        if (!hasUpdates(command)) {
            throw new IllegalArgumentException("No transaction update fields provided.");
        }

        List<TransactionDO> transactions = findAll();
        Map<String, TransactionDO> byId = new LinkedHashMap<>();
        for (TransactionDO transaction : transactions) {
            byId.put(transaction.transactionId(), transaction);
        }

        List<String> requestedIds = uniqueIds(command.transactionIds());
        List<String> missingIds =
                requestedIds.stream()
                        .filter(transactionId -> !byId.containsKey(transactionId))
                        .toList();
        if (!missingIds.isEmpty()) {
            throw new NoSuchElementException(
                    "Transaction not found: " + String.join(", ", missingIds));
        }

        List<TransactionDO> updated =
                requestedIds.stream()
                        .map(byId::get)
                        .map(transaction -> applyUpdate(transaction, command))
                        .toList();
        transactionRepository.writeAll(
                transactions.stream().map(transactionConverter::toCsv).toList());
        return updated;
    }

    public TransactionView toView(TransactionDO transaction, Map<String, String> displayNamesById) {
        return new TransactionView(
                transaction,
                transactionServiceHelper.displayCategory(transaction, displayNamesById));
    }

    private static LocalDate resolveStart(TransactionQueryCommand command) {
        if (command.startDate() != null) {
            return command.startDate();
        }
        YearMonth month = command.month() == null ? YearMonth.now() : command.month();
        return month.atDay(1);
    }

    private static LocalDate resolveEnd(TransactionQueryCommand command) {
        if (command.endDate() != null) {
            return command.endDate();
        }
        YearMonth month = command.month() == null ? YearMonth.now() : command.month();
        return month.atEndOfMonth();
    }

    private static TransactionDO applyUpdate(
            TransactionDO transaction, UpdateTransactionsCommand command) {
        if (StringUtils.isNotBlank(command.customName())) {
            transaction.withCustomName(command.customName());
        }
        if (StringUtils.isNotBlank(command.categoryId())) {
            transaction.withLocalCategoryId(command.categoryId());
        }
        if (command.date() != null) {
            transaction.withCustomDate(command.date());
        }
        return transaction;
    }

    private static boolean hasUpdates(UpdateTransactionsCommand command) {
        return StringUtils.isNotBlank(command.customName())
                || StringUtils.isNotBlank(command.categoryId())
                || command.date() != null;
    }

    private static List<String> uniqueIds(Set<String> transactionIds) {
        return transactionIds.stream().distinct().toList();
    }
}
