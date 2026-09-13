package com.localbudget.app.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.localbudget.app.TestFixtures;
import com.localbudget.app.api.model.response.TransactionResponse;
import com.localbudget.app.data.model.TransactionCsvRecord;
import com.localbudget.app.domain.model.TransactionDO;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionConverterTest {

    private final TransactionConverter converter = new TransactionConverter();

    @Test
    void fromPlaidMapsSdkTransactionToDomainObject() {
        Transaction plaidTransaction =
                new Transaction()
                        .transactionId("txn-1")
                        .accountId("acc-checking")
                        .date(LocalDate.parse("2026-06-28"))
                        .name("Coffee Shop")
                        .merchantName("Coffee Merchant")
                        .amount(12.34)
                        .personalFinanceCategory(
                                new PersonalFinanceCategory()
                                        .primary("FOOD_AND_DRINK")
                                        .detailed("FOOD_AND_DRINK_COFFEE"))
                        .pending(true)
                        .paymentChannel(Transaction.PaymentChannelEnum.IN_STORE);

        TransactionDO transaction =
                converter.fromPlaid(
                        TestFixtures.plaidItem(),
                        List.of(TestFixtures.checkingAccount()),
                        plaidTransaction);

        assertThat(transaction.transactionId()).isEqualTo("txn-1");
        assertThat(transaction.pendingTransactionId()).isNull();
        assertThat(transaction.plaidItemId()).isEqualTo("item-1");
        assertThat(transaction.accountName()).isEqualTo("Main Checking");
        assertThat(transaction.amount()).isEqualByComparingTo("12.34");
        assertThat(transaction.primaryCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(transaction.detailedCategory()).isEqualTo("FOOD_AND_DRINK_COFFEE");
        assertThat(transaction.localCategory()).isNull();
        assertThat(transaction.localCategoryId()).isNull();
        assertThat(transaction.pending()).isTrue();
        assertThat(transaction.excluded()).isFalse();
        assertThat(transaction.paymentChannel()).isEqualTo("in store");
    }

    @Test
    void postedPlaidTransactionRoundTripsThroughCsvWithPendingLink() {
        Transaction plaidTransaction =
                new Transaction()
                        .transactionId("txn-2")
                        .pendingTransactionId("pending-2")
                        .accountId("acc-checking")
                        .date(LocalDate.parse("2026-06-29"))
                        .name("Paycheck")
                        .merchantName("Employer")
                        .amount(-2500.0)
                        .pending(false)
                        .paymentChannel(Transaction.PaymentChannelEnum.ONLINE);

        TransactionDO transaction =
                converter.fromPlaid(
                        TestFixtures.plaidItem(),
                        List.of(TestFixtures.checkingAccount()),
                        plaidTransaction);
        assertThat(transaction.pendingTransactionId()).isEqualTo("pending-2");
        TransactionCsvRecord csvRecord = converter.toCsv(transaction);

        assertThat(csvRecord.transactionId()).isEqualTo("txn-2");
        assertThat(csvRecord.pendingTransactionId()).isEqualTo("pending-2");
        assertThat(csvRecord.plaidItemId()).isEqualTo("item-1");
        assertThat(csvRecord.accountName()).isEqualTo("Main Checking");
        assertThat(new BigDecimal(csvRecord.amount())).isEqualByComparingTo("-2500.0");
        assertThat(csvRecord.pending()).isEqualTo("false");
        assertThat(csvRecord.excluded()).isEqualTo("false");
        assertThat(csvRecord.paymentChannel()).isEqualTo("online");
        assertThat(csvRecord.localCategoryId()).isNull();
        assertThat(csvRecord.customName()).isNull();
        assertThat(csvRecord.customDate()).isNull();

        TransactionDO restored = converter.fromCsv(csvRecord);
        assertThat(restored.pendingTransactionId()).isEqualTo("pending-2");
        assertThat(restored.transactionId()).isEqualTo("txn-2");
        assertThat(restored.pending()).isFalse();
    }

    @Test
    void fromCsvDerivesCategoryIdFromLegacyLocalCategoryWhenPossible() {
        TransactionCsvRecord csvRecord =
                new TransactionCsvRecord(
                        "txn-legacy",
                        "item-1",
                        "acc-checking",
                        "Main Checking",
                        "2026-06-29",
                        "Coffee",
                        "Coffee Merchant",
                        "12.34",
                        "FOOD_AND_DRINK",
                        "FOOD_AND_DRINK_COFFEE",
                        "Dining & Drinks",
                        "false",
                        "false",
                        "in store",
                        null,
                        "Custom coffee",
                        "2026-07-01");

        TransactionDO transaction = converter.fromCsv(csvRecord);

        assertThat(transaction.pendingTransactionId()).isNull();
        assertThat(transaction.localCategory()).isEqualTo("Dining & Drinks");
        assertThat(transaction.localCategoryId()).isEqualTo("dining-drinks");
        assertThat(transaction.customName()).isEqualTo("Custom coffee");
        assertThat(transaction.customDate()).isEqualTo(LocalDate.parse("2026-07-01"));
    }

    @Test
    void toResponseIncludesAssignedAndPlaidCategoryFields() {
        TransactionDO transaction =
                TestFixtures.transaction(
                                "txn-3",
                                LocalDate.parse("2026-06-29"),
                                new BigDecimal("12.34"),
                                "FOOD_AND_DRINK")
                        .withLocalCategoryId("dining-drinks");

        TransactionResponse response = converter.toResponse(transaction, "Dining & Drinks");

        assertThat(response.category()).isEqualTo("Dining & Drinks");
        assertThat(response.assignedCategoryId()).isEqualTo("dining-drinks");
        assertThat(response.assignedCategoryName()).isEqualTo("Dining & Drinks");
        assertThat(response.primaryCategory()).isEqualTo("FOOD_AND_DRINK");
        assertThat(response.detailedCategory()).isEqualTo("FOOD_AND_DRINK_DETAIL");
    }

    @Test
    void toResponseUsesCustomNameAndDateWhenPresent() {
        TransactionDO transaction =
                TestFixtures.transaction(
                                "txn-4",
                                LocalDate.parse("2026-06-29"),
                                new BigDecimal("12.34"),
                                "FOOD_AND_DRINK")
                        .withCustomName("Custom coffee")
                        .withCustomDate(LocalDate.parse("2026-07-01"));

        TransactionResponse response = converter.toResponse(transaction, "FOOD_AND_DRINK");

        assertThat(response.name()).isEqualTo("Custom coffee");
        assertThat(response.date()).isEqualTo(LocalDate.parse("2026-07-01"));
        assertThat(transaction.name()).isEqualTo("Transaction txn-4");
        assertThat(transaction.date()).isEqualTo(LocalDate.parse("2026-06-29"));
    }
}
