package com.payflow.application.query;

import com.payflow.domain.model.transaction.TransactionNotFoundException;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.repository.TransactionRepository;
import com.payflow.domain.repository.WalletRepository;
import org.springframework.data.domain.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionQueryHandlerTest {

    @Mock private TransactionRepository repository;
    @Mock private WalletRepository walletRepository;
    @Mock private Wallet wallet;

    @InjectMocks
    private TransactionQueryHandler handler;

    @Test
    void shouldThrowWhenTransactionNotFound() {
        UUID transactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(repository.findByIdAndUserId(transactionId, userId)).thenReturn(Optional.empty());
        var query = new TransactionQueryHandler.GetTransactionQuery(transactionId, userId);
        assertThatThrownBy(() -> handler.handle(query)).isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void shouldThrowWhenWalletIdIsNull() {
        UUID userId = UUID.randomUUID();
        var query = new TransactionQueryHandler.GetTransactionsQuery(userId, null, null, null, Pageable.unpaged());

        assertThatThrownBy(() -> handler.handle(query))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(walletRepository, repository);
    }

    @Test
    void shouldPassOwnershipCheckAndDelegateWhenWalletBelongsToUser() {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(walletRepository.findByIdAndUserId(walletId, userId)).thenReturn(Optional.of(wallet));
        when(repository.findFiltered(walletId, null, null, Pageable.unpaged())).thenReturn(Page.empty());

        handler.handle(new TransactionQueryHandler.GetTransactionsQuery(userId, walletId, null, null, Pageable.unpaged()));

        verify(walletRepository).findByIdAndUserId(walletId, userId);
        verify(repository).findFiltered(walletId, null, null, Pageable.unpaged());
    }

    @Test
    void shouldThrowAndNotQueryRepositoryWhenWalletNotOwnedByUser() {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(walletRepository.findByIdAndUserId(walletId, userId)).thenReturn(Optional.empty());

        var query = new TransactionQueryHandler.GetTransactionsQuery(userId, walletId, null, null, Pageable.unpaged());
        assertThatThrownBy(() -> handler.handle( query))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(repository);
    }
}
