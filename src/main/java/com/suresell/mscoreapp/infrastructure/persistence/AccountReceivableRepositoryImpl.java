package com.suresell.mscoreapp.infrastructure.persistence;

import com.suresell.mscoreapp.application.dto.AccountReceivable;
import com.suresell.mscoreapp.application.dto.DebtTransaction;
import com.suresell.mscoreapp.application.usecase.AccountReceivableEntityMapper;
import com.suresell.mscoreapp.domain.model.AccountReceivableEntity;
import com.suresell.mscoreapp.domain.model.DebtTransactionEntity;
import com.suresell.mscoreapp.domain.port.out.AccountReceivableRepository;
import com.suresell.mscoreapp.infrastructure.persistence.jpa.AccountReceivablePanacheRepository;
import com.suresell.mscoreapp.infrastructure.persistence.jpa.DebtTransactionPanacheRepository;
import com.suresell.mscoreapp.shared.enums.AccountStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class AccountReceivableRepositoryImpl implements AccountReceivableRepository {

    private static final Logger logger = LoggerFactory.getLogger(AccountReceivableRepositoryImpl.class);

    private final AccountReceivableEntityMapper mapper;
    private final AccountReceivablePanacheRepository accountReceivableJpaRepository;
    private final DebtTransactionPanacheRepository debtTransactionJpaRepository;

    public AccountReceivableRepositoryImpl(AccountReceivableEntityMapper mapper,
                                           AccountReceivablePanacheRepository accountReceivableJpaRepository,
                                           DebtTransactionPanacheRepository debtTransactionJpaRepository) {
        this.mapper = mapper;
        this.accountReceivableJpaRepository = accountReceivableJpaRepository;
        this.debtTransactionJpaRepository = debtTransactionJpaRepository;
    }

    @Override
    public List<AccountReceivable> findAllAccounts() {
        logger.debug("Obteniendo todas las cuentas por cobrar");
        return accountReceivableJpaRepository.findAll().stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<AccountReceivable> findById(String id) {
        logger.debug("Buscando cuenta por ID: {}", id);
        return accountReceivableJpaRepository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<AccountReceivable> findByCustomerDocument(String customerDocument) {
        logger.debug("Buscando cuenta por documento: {}", customerDocument);
        return accountReceivableJpaRepository.findByCustomerDocument(customerDocument)
                .map(mapper::toDomain);
    }

    @Override
    public List<AccountReceivable> findByStatus(AccountStatus status) {
        logger.debug("Buscando cuentas con estado: {}", status);
        return accountReceivableJpaRepository.findByStatus(status).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AccountReceivable> findAccountsWithDebt() {
        logger.debug("Buscando cuentas con deuda pendiente");
        return accountReceivableJpaRepository.findByTotalDebtGreaterThanOrderByTotalDebtDesc(BigDecimal.ZERO).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AccountReceivable> findOverdueAccounts(int daysOverdue) {
        LocalDate cutoffDate = LocalDate.now().minusDays(daysOverdue);
        logger.debug("Buscando cuentas vencidas desde: {}", cutoffDate);
        return accountReceivableJpaRepository.findByTotalDebtGreaterThanAndLastTransactionDateLessThanEqual(BigDecimal.ZERO, cutoffDate)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AccountReceivable> findByDebtRange(BigDecimal minDebt, BigDecimal maxDebt) {
        logger.debug("Buscando cuentas con deuda entre: {} - {}", minDebt, maxDebt);
        return accountReceivableJpaRepository.findByTotalDebtBetweenOrderByTotalDebtDesc(minDebt, maxDebt).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AccountReceivable> findByDateRange(LocalDate startDate, LocalDate endDate) {
        logger.debug("Buscando cuentas en rango: {} - {}", startDate, endDate);
        return accountReceivableJpaRepository.findByLastTransactionDateBetweenOrderByLastTransactionDateDesc(startDate, endDate)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AccountReceivable create(AccountReceivable account) {
        logger.debug("➕ Creando nueva cuenta para: {}", account.getCustomerName());
        AccountReceivableEntity newEntity = mapper.toEntity(account);
        accountReceivableJpaRepository.save(newEntity);
        logger.info("Cuenta creada para cliente: {}", account.getCustomerName());
        return mapper.toDomain(newEntity);
    }

    @Override
    @Transactional
    public AccountReceivable update(AccountReceivable account) {
        logger.debug("🔄 Actualizando cuenta: {}", account.getId());
        AccountReceivableEntity existingEntity = accountReceivableJpaRepository.findById(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + account.getId()));

        mapper.updateEntity(existingEntity, account);
        updateTransactions(account);

        logger.info("Cuenta actualizada: {}", account.getCustomerName());
        return mapper.toDomain(existingEntity);
    }

    /**
     * 🔴 El libro de cartera es APPEND-ONLY, y esto lo estaba borrando.
     *
     * <p>Se midió en staging (2026-09-05) con un cliente de prueba: deuda de
     * 30.000 y abono de 20.000. La cuenta decía 10.000; el libro tenía UNA
     * fila, el abono. Este método hacía {@code deleteByAccountId} y volvía a
     * escribir los movimientos del objeto en memoria — y el mapper carga la
     * cuenta con {@code transactions} vacío, así que cada operación borraba
     * la historia de la anterior. Nunca se notó porque la cartera tenía cero
     * filas en producción.
     *
     * <p>Ahora solo se INSERTAN los movimientos que la operación añadió. Y desde
     * V44 de la cadena de ventas la base no deja borrar ni editar el libro
     * aunque alguien vuelva a escribir el {@code delete}.
     */
    private void updateTransactions(AccountReceivable account) {
        for (DebtTransaction transaction : account.getTransactions()) {
            if (transaction.getId() != null && debtTransactionJpaRepository.existsById(transaction.getId())) {
                continue;
            }
            DebtTransactionEntity transactionEntity = mapper.transactionToEntity(transaction, account.getId());
            debtTransactionJpaRepository.save(transactionEntity);
        }
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        // Una cuenta con historia no se borra: se cierra (status CLOSED). El
        // libro es append-only por privilegio desde V44; borrar fallaría igual.
        throw new IllegalStateException(
                "La cartera no se borra: cierra la cuenta. Sus movimientos son historia y se conservan.");
    }

    @Override
    public boolean existsByCustomerDocument(String customerDocument) {
        return accountReceivableJpaRepository.existsByCustomerDocument(customerDocument);
    }

    @Override
    public List<DebtTransaction> findTransactionsByAccountId(String accountId) {
        logger.debug("Buscando transacciones de cuenta: {}", accountId);
        return debtTransactionJpaRepository.findByAccountIdOrderByTransactionDateDesc(accountId).stream()
                .map(mapper::transactionToDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<DebtTransaction> findTransactionsByDateRange(LocalDate startDate, LocalDate endDate) {
        logger.debug("Buscando transacciones en rango: {} - {}", startDate, endDate);
        return debtTransactionJpaRepository.findByTransactionDateBetweenOrderByTransactionDateDesc(startDate, endDate).stream()
                .map(mapper::transactionToDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public DebtTransaction saveTransaction(String accountId, DebtTransaction transaction) {
        logger.debug("Guardando transacción para cuenta: {}", accountId);
        DebtTransactionEntity entity = mapper.transactionToEntity(transaction, accountId);
        debtTransactionJpaRepository.save(entity);
        return mapper.transactionToDomain(entity);
    }

    @Override
    public BigDecimal getTotalDebtAmount() {
        return accountReceivableJpaRepository.getTotalDebtAmount();
    }

    @Override
    public long countAccountsWithDebt() {
        return accountReceivableJpaRepository.countByTotalDebtGreaterThan(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal getAverageDebtAmount() {
        return accountReceivableJpaRepository.getAverageDebtAmount();
    }
}
