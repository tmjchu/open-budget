package com.localbudget.app.converter;

import com.localbudget.app.api.model.response.TransactionResponse;
import com.localbudget.app.data.model.TransactionCsvRecord;
import com.localbudget.app.domain.model.AccountDO;
import com.localbudget.app.domain.model.CategoryDefaults;
import com.localbudget.app.domain.model.PlaidItem;
import com.localbudget.app.domain.model.TransactionDO;
import com.plaid.client.model.PersonalFinanceCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class TransactionConverter {

    public TransactionDO fromPlaid(
            PlaidItem plaidItem,
            List<AccountDO> trackedAccounts,
            com.plaid.client.model.Transaction transaction) {
        var accountById =
                trackedAccounts.stream()
                        .collect(Collectors.toMap(AccountDO::accountId, Function.identity()));
        AccountDO account = accountById.get(transaction.getAccountId());
        PersonalFinanceCategory category = transaction.getPersonalFinanceCategory();

        return TransactionDO.builder()
                .transactionId(transaction.getTransactionId())
                .pendingTransactionId(transaction.getPendingTransactionId())
                .plaidItemId(plaidItem.plaidItemId())
                .accountId(transaction.getAccountId())
                .accountName(account == null ? null : account.name())
                .date(transaction.getDate())
                .name(transaction.getName())
                .merchantName(transaction.getMerchantName())
                .amount(amount(transaction.getAmount()))
                .primaryCategory(category == null ? null : category.getPrimary())
                .detailedCategory(category == null ? null : category.getDetailed())
                .pending(Boolean.TRUE.equals(transaction.getPending()))
                .excluded(false)
                .paymentChannel(value(transaction.getPaymentChannel()))
                .build();
    }

    public TransactionDO fromCsv(TransactionCsvRecord transactionCsvRecord) {
        return TransactionDO.builder()
                .transactionId(transactionCsvRecord.transactionId())
                .pendingTransactionId(transactionCsvRecord.pendingTransactionId())
                .plaidItemId(transactionCsvRecord.plaidItemId())
                .accountId(transactionCsvRecord.accountId())
                .accountName(transactionCsvRecord.accountName())
                .date(LocalDate.parse(transactionCsvRecord.date()))
                .name(transactionCsvRecord.name())
                .merchantName(transactionCsvRecord.merchantName())
                .amount(parseAmount(transactionCsvRecord.amount()))
                .primaryCategory(transactionCsvRecord.primaryCategory())
                .detailedCategory(transactionCsvRecord.detailedCategory())
                .localCategory(transactionCsvRecord.localCategory())
                .localCategoryId(
                        localCategoryId(
                                transactionCsvRecord.localCategoryId(),
                                transactionCsvRecord.localCategory()))
                .pending(Boolean.parseBoolean(transactionCsvRecord.pending()))
                .excluded(Boolean.parseBoolean(transactionCsvRecord.excluded()))
                .paymentChannel(transactionCsvRecord.paymentChannel())
                .customName(transactionCsvRecord.customName())
                .customDate(parseDate(transactionCsvRecord.customDate()))
                .build();
    }

    public TransactionCsvRecord toCsv(TransactionDO transaction) {
        return new TransactionCsvRecord(
                transaction.transactionId(),
                transaction.plaidItemId(),
                transaction.accountId(),
                transaction.accountName(),
                transaction.date().toString(),
                transaction.name(),
                transaction.merchantName(),
                transaction.amount().toPlainString(),
                transaction.primaryCategory(),
                transaction.detailedCategory(),
                transaction.localCategory(),
                String.valueOf(transaction.pending()),
                String.valueOf(transaction.excluded()),
                transaction.paymentChannel(),
                transaction.localCategoryId(),
                transaction.customName(),
                transaction.customDate() == null ? null : transaction.customDate().toString(),
                transaction.pendingTransactionId());
    }

    public TransactionResponse toResponse(TransactionDO transaction, String categoryDisplayName) {
        return new TransactionResponse(
                transaction.transactionId(),
                transaction.accountId(),
                transaction.accountName(),
                transaction.effectiveDate(),
                transaction.effectiveName(),
                transaction.merchantName(),
                transaction.amount(),
                categoryDisplayName,
                transaction.localCategoryId(),
                assignedCategoryName(transaction, categoryDisplayName),
                transaction.primaryCategory(),
                transaction.detailedCategory(),
                transaction.pending(),
                transaction.excluded(),
                transaction.paymentChannel());
    }

    private static BigDecimal parseAmount(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static BigDecimal amount(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static String value(Object value) {
        return value == null ? null : value.toString();
    }

    private static String localCategoryId(String localCategoryId, String legacyLocalCategory) {
        if (localCategoryId != null && !localCategoryId.isBlank()) {
            return localCategoryId;
        }
        return legacyLocalCategory == null || legacyLocalCategory.isBlank()
                ? null
                : CategoryDefaults.categoryIdForDisplayName(legacyLocalCategory).orElse(null);
    }

    private static String assignedCategoryName(
            TransactionDO transaction, String categoryDisplayName) {
        if (transaction.localCategoryId() != null && !transaction.localCategoryId().isBlank()) {
            return categoryDisplayName;
        }
        if (transaction.localCategory() != null && !transaction.localCategory().isBlank()) {
            return categoryDisplayName;
        }
        return null;
    }
}
