/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.main.offer;

import bisq.desktop.util.BSFormatter;

import bisq.core.app.BisqEnvironment;
import bisq.core.arbitration.Arbitrator;
import bisq.core.btc.AddressEntry;
import bisq.core.btc.Restrictions;
import bisq.core.btc.listeners.BalanceListener;
import bisq.core.btc.wallet.BsqBalanceListener;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.filter.FilterManager;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.locale.TradeCurrency;
import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OfferUtil;
import bisq.core.offer.OpenOfferManager;
import bisq.core.payment.AccountAgeWitnessService;
import bisq.core.payment.BankAccount;
import bisq.core.payment.CountryBasedPaymentAccount;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.SameBankAccount;
import bisq.core.payment.SepaAccount;
import bisq.core.payment.SepaInstantAccount;
import bisq.core.payment.SpecificBanksAccount;
import bisq.core.provider.fee.FeeService;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.handlers.TransactionResultHandler;
import bisq.core.user.Preferences;
import bisq.core.user.User;

import bisq.network.p2p.P2PService;

import bisq.common.app.Version;
import bisq.common.crypto.KeyRing;
import bisq.common.util.Utilities;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;

import com.google.inject.Inject;

import com.google.common.collect.Lists;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.SetChangeListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public abstract class EditableOfferDataModel extends OfferDataModel implements BsqBalanceListener {
    protected final OpenOfferManager openOfferManager;
    private final BsqWalletService bsqWalletService;
    private final Preferences preferences;
    protected final User user;
    private final KeyRing keyRing;
    private final P2PService p2PService;
    protected final PriceFeedService priceFeedService;
    final String shortOfferId;
    private final FilterManager filterManager;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final TradeWalletService tradeWalletService;
    private final FeeService feeService;
    private final BSFormatter formatter;
    private final String offerId;
    private final BalanceListener btcBalanceListener;
    private final SetChangeListener<PaymentAccount> paymentAccountsChangeListener;

    private OfferPayload.Direction direction;
    private TradeCurrency tradeCurrency;
    private final StringProperty tradeCurrencyCode = new SimpleStringProperty();
    private final BooleanProperty useMarketBasedPrice = new SimpleBooleanProperty();
    //final BooleanProperty isMainNet = new SimpleBooleanProperty();
    //final BooleanProperty isFeeFromFundingTxSufficient = new SimpleBooleanProperty();

    // final ObjectProperty<Coin> feeFromFundingTxProperty = new SimpleObjectProperty(Coin.NEGATIVE_SATOSHI);
    private final ObjectProperty<Coin> amount = new SimpleObjectProperty<>();
    private final ObjectProperty<Coin> minAmount = new SimpleObjectProperty<>();
    private final ObjectProperty<Price> price = new SimpleObjectProperty<>();
    private final ObjectProperty<Volume> volume = new SimpleObjectProperty<>();
    private final ObjectProperty<Coin> buyerSecurityDeposit = new SimpleObjectProperty<>();
    private final Coin sellerSecurityDeposit;

    private final ObservableList<PaymentAccount> paymentAccounts = FXCollections.observableArrayList();

    protected PaymentAccount paymentAccount;
    boolean isTabSelected;
    private double marketPriceMargin = 0;
    private Coin txFeeFromFeeService;
    private boolean marketPriceAvailable;
    private int feeTxSize = 260; // size of typical tx with 1 input
    private int feeTxSizeEstimationRecursionCounter;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public EditableOfferDataModel(OpenOfferManager openOfferManager, BtcWalletService btcWalletService, BsqWalletService bsqWalletService,
                                  Preferences preferences, User user, KeyRing keyRing, P2PService p2PService,
                                  PriceFeedService priceFeedService, FilterManager filterManager,
                                  AccountAgeWitnessService accountAgeWitnessService, TradeWalletService tradeWalletService,
                                  FeeService feeService, BSFormatter formatter) {
        super(btcWalletService);

        this.openOfferManager = openOfferManager;
        this.bsqWalletService = bsqWalletService;
        this.preferences = preferences;
        this.user = user;
        this.keyRing = keyRing;
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.filterManager = filterManager;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.tradeWalletService = tradeWalletService;
        this.feeService = feeService;
        this.formatter = formatter;

        offerId = Utilities.getRandomPrefix(5, 8) + "-" +
                UUID.randomUUID().toString() + "-" +
                Version.VERSION.replace(".", "");
        shortOfferId = Utilities.getShortId(offerId);
        addressEntry = btcWalletService.getOrCreateAddressEntry(offerId, AddressEntry.Context.OFFER_FUNDING);

        useMarketBasedPrice.set(preferences.isUsePercentageBasedPrice());
        buyerSecurityDeposit.set(preferences.getBuyerSecurityDepositAsCoin());
        sellerSecurityDeposit = Restrictions.getSellerSecurityDeposit();

        btcBalanceListener = new BalanceListener(getAddressEntry().getAddress()) {
            @Override
            public void onBalanceChanged(Coin balance, Transaction tx) {
                updateBalance();

               /* if (isMainNet.get()) {
                    SettableFuture<Coin> future = blockchainService.requestFee(tx.getHashAsString());
                    Futures.addCallback(future, new FutureCallback<Coin>() {
                        public void onSuccess(Coin fee) {
                            UserThread.execute(() -> feeFromFundingTxProperty.set(fee));
                        }

                        public void onFailure(@NotNull Throwable throwable) {
                            UserThread.execute(() -> new Popup<>()
                                    .warning("We did not get a response for the request of the mining fee used " +
                                            "in the funding transaction.\n\n" +
                                            "Are you sure you used a sufficiently high fee of at least " +
                                            formatter.formatCoinWithCode(FeePolicy.getMinRequiredFeeForFundingTx()) + "?")
                                    .actionButtonText("Yes, I used a sufficiently high fee.")
                                    .onAction(() -> feeFromFundingTxProperty.set(FeePolicy.getMinRequiredFeeForFundingTx()))
                                    .closeButtonText("No. Let's cancel that payment.")
                                    .onClose(() -> feeFromFundingTxProperty.set(Coin.ZERO))
                                    .show());
                        }
                    });
                }*/
            }
        };

        paymentAccountsChangeListener = change -> fillPaymentAccounts();
    }

    @Override
    public void activate() {
        addListeners();

        if (isTabSelected)
            priceFeedService.setCurrencyCode(tradeCurrencyCode.get());

        updateBalance();
    }

    @Override
    protected void deactivate() {
        removeListeners();
    }

    private void addListeners() {
        btcWalletService.addBalanceListener(btcBalanceListener);
        if (BisqEnvironment.isBaseCurrencySupportingBsq())
            bsqWalletService.addBsqBalanceListener(this);
        user.getPaymentAccountsAsObservable().addListener(paymentAccountsChangeListener);
    }


    private void removeListeners() {
        btcWalletService.removeBalanceListener(btcBalanceListener);
        if (BisqEnvironment.isBaseCurrencySupportingBsq())
            bsqWalletService.removeBsqBalanceListener(this);
        user.getPaymentAccountsAsObservable().removeListener(paymentAccountsChangeListener);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // called before activate()
    public boolean initWithData(OfferPayload.Direction direction, TradeCurrency tradeCurrency) {
        this.direction = direction;
        this.tradeCurrency = tradeCurrency;

        fillPaymentAccounts();

        PaymentAccount account;
        PaymentAccount lastSelectedPaymentAccount = preferences.getSelectedPaymentAccountForCreateOffer();
        if (lastSelectedPaymentAccount != null &&
                user.getPaymentAccounts() != null &&
                user.getPaymentAccounts().contains(lastSelectedPaymentAccount)) {
            account = lastSelectedPaymentAccount;
        } else {
            account = user.findFirstPaymentAccountWithCurrency(tradeCurrency);
        }

        if (account != null) {
            this.paymentAccount = account;
        } else {
            Optional<PaymentAccount> paymentAccountOptional = paymentAccounts.stream().findAny();
            if (paymentAccountOptional.isPresent()) {
                this.paymentAccount = paymentAccountOptional.get();

            } else {
                log.warn("PaymentAccount not available. Should never get called as in offer view you should not be able to open a create offer view");
                return false;
            }
        }

        setTradeCurrencyFromPaymentAccount(paymentAccount);
        tradeCurrencyCode.set(this.tradeCurrency.getCode());

        priceFeedService.setCurrencyCode(tradeCurrencyCode.get());

        // We request to get the actual estimated fee
        requestTxFee();

        // Set the default values (in rare cases if the fee request was not done yet we get the hard coded default values)
        // But offer creation happens usually after that so we should have already the value from the estimation service.
        txFeeFromFeeService = feeService.getTxFee(feeTxSize);

        calculateVolume();
        calculateTotalToPay();
        updateBalance();

        return true;
    }

    void onTabSelected(boolean isSelected) {
        this.isTabSelected = isSelected;
        if (isTabSelected)
            priceFeedService.setCurrencyCode(tradeCurrencyCode.get());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("ConstantConditions")
    Offer createAndGetOffer() {
        final boolean useMarketBasedPriceValue = isUseMarketBasedPriceValue();
        long priceAsLong = price.get() != null && !useMarketBasedPriceValue ? price.get().getValue() : 0L;
        String currencyCode = tradeCurrencyCode.get();
        boolean isCryptoCurrency = CurrencyUtil.isCryptoCurrency(currencyCode);
        String baseCurrencyCode = isCryptoCurrency ? currencyCode : Res.getBaseCurrencyCode();
        String counterCurrencyCode = isCryptoCurrency ? Res.getBaseCurrencyCode() : currencyCode;

        double marketPriceMarginParam = useMarketBasedPriceValue ? marketPriceMargin : 0;
        long amount = this.amount.get() != null ? this.amount.get().getValue() : 0L;
        long minAmount = this.minAmount.get() != null ? this.minAmount.get().getValue() : 0L;

        ArrayList<String> acceptedCountryCodes = null;
        if (paymentAccount instanceof SepaAccount) {
            acceptedCountryCodes = new ArrayList<>();
            acceptedCountryCodes.addAll(((SepaAccount) paymentAccount).getAcceptedCountryCodes());
        } else if (paymentAccount instanceof SepaInstantAccount) {
            acceptedCountryCodes = new ArrayList<>();
            acceptedCountryCodes.addAll(((SepaInstantAccount) paymentAccount).getAcceptedCountryCodes());
        } else if (paymentAccount instanceof CountryBasedPaymentAccount) {
            acceptedCountryCodes = new ArrayList<>();
            acceptedCountryCodes.add(((CountryBasedPaymentAccount) paymentAccount).getCountry().code);
        }

        ArrayList<String> acceptedBanks = null;
        if (paymentAccount instanceof SpecificBanksAccount) {
            acceptedBanks = new ArrayList<>(((SpecificBanksAccount) paymentAccount).getAcceptedBanks());
        } else if (paymentAccount instanceof SameBankAccount) {
            acceptedBanks = new ArrayList<>();
            acceptedBanks.add(((SameBankAccount) paymentAccount).getBankId());
        }

        String bankId = paymentAccount instanceof BankAccount ? ((BankAccount) paymentAccount).getBankId() : null;

        // That is optional and set to null if not supported (AltCoins, OKPay,...)
        String countryCode = paymentAccount instanceof CountryBasedPaymentAccount ? ((CountryBasedPaymentAccount) paymentAccount).getCountry().code : null;

        checkNotNull(p2PService.getAddress(), "Address must not be null");
        checkNotNull(getMakerFee(), "makerFee must not be null");

        long maxTradeLimit = getMaxTradeLimit();
        long maxTradePeriod = paymentAccount.getPaymentMethod().getMaxTradePeriod();

        // reserved for future use cases
        // Use null values if not set
        boolean isPrivateOffer = false;
        boolean useAutoClose = false;
        boolean useReOpenAfterAutoClose = false;
        long lowerClosePrice = 0;
        long upperClosePrice = 0;
        String hashOfChallenge = null;
        HashMap<String, String> extraDataMap = null;
        if (CurrencyUtil.isFiatCurrency(currencyCode)) {
            extraDataMap = new HashMap<>();
            final String myWitnessHashAsHex = accountAgeWitnessService.getMyWitnessHashAsHex(paymentAccount.getPaymentAccountPayload());
            extraDataMap.put(OfferPayload.ACCOUNT_AGE_WITNESS_HASH, myWitnessHashAsHex);
        }

        Coin buyerSecurityDepositAsCoin = buyerSecurityDeposit.get();
        checkArgument(buyerSecurityDepositAsCoin.compareTo(Restrictions.getMaxBuyerSecurityDeposit()) <= 0,
                "securityDeposit must be not exceed " +
                        Restrictions.getMaxBuyerSecurityDeposit().toFriendlyString());
        checkArgument(buyerSecurityDepositAsCoin.compareTo(Restrictions.getMinBuyerSecurityDeposit()) >= 0,
                "securityDeposit must be not be less than " +
                        Restrictions.getMinBuyerSecurityDeposit().toFriendlyString());

        checkArgument(!filterManager.isCurrencyBanned(currencyCode),
                Res.get("offerbook.warning.currencyBanned"));
        checkArgument(!filterManager.isPaymentMethodBanned(paymentAccount.getPaymentMethod()),
                Res.get("offerbook.warning.paymentMethodBanned"));

        OfferPayload offerPayload = new OfferPayload(offerId,
                new Date().getTime(),
                p2PService.getAddress(),
                keyRing.getPubKeyRing(),
                OfferPayload.Direction.valueOf(direction.name()),
                priceAsLong,
                marketPriceMarginParam,
                useMarketBasedPriceValue,
                amount,
                minAmount,
                baseCurrencyCode,
                counterCurrencyCode,
                Lists.newArrayList(user.getAcceptedArbitratorAddresses()),
                Lists.newArrayList(user.getAcceptedMediatorAddresses()),
                paymentAccount.getPaymentMethod().getId(),
                paymentAccount.getId(),
                null,
                countryCode,
                acceptedCountryCodes,
                bankId,
                acceptedBanks,
                Version.VERSION,
                btcWalletService.getLastBlockSeenHeight(),
                txFeeFromFeeService.value,
                getMakerFee().value,
                isCurrencyForMakerFeeBtc(),
                buyerSecurityDepositAsCoin.value,
                sellerSecurityDeposit.value,
                maxTradeLimit,
                maxTradePeriod,
                useAutoClose,
                useReOpenAfterAutoClose,
                upperClosePrice,
                lowerClosePrice,
                isPrivateOffer,
                hashOfChallenge,
                extraDataMap,
                Version.TRADE_PROTOCOL_VERSION);
        Offer offer = new Offer(offerPayload);
        offer.setPriceFeedService(priceFeedService);
        return offer;
    }

    // This works only if have already funds in the wallet
    public void estimateTxSize() {
        txFeeFromFeeService = feeService.getTxFee(feeTxSize);
        Address fundingAddress = btcWalletService.getOrCreateAddressEntry(AddressEntry.Context.AVAILABLE).getAddress();
        Address reservedForTradeAddress = btcWalletService.getOrCreateAddressEntry(offerId, AddressEntry.Context.RESERVED_FOR_TRADE).getAddress();
        Address changeAddress = btcWalletService.getOrCreateAddressEntry(AddressEntry.Context.AVAILABLE).getAddress();

        Coin reservedFundsForOffer = getSecurityDeposit();
        if (!isBuyOffer())
            reservedFundsForOffer = reservedFundsForOffer.add(amount.get());

        checkNotNull(user.getAcceptedArbitrators(), "user.getAcceptedArbitrators() must not be null");
        checkArgument(!user.getAcceptedArbitrators().isEmpty(), "user.getAcceptedArbitrators() must not be empty");
        String dummyArbitratorAddress = user.getAcceptedArbitrators().get(0).getBtcAddress();
        try {
            log.info("We create a dummy tx to see if our estimated size is in the accepted range. feeTxSize={}," +
                            " txFee based on feeTxSize: {}, recommended txFee is {} sat/byte",
                    feeTxSize, txFeeFromFeeService.toFriendlyString(), feeService.getTxFeePerByte());
            Transaction tradeFeeTx = tradeWalletService.estimateBtcTradingFeeTxSize(
                    fundingAddress,
                    reservedForTradeAddress,
                    changeAddress,
                    reservedFundsForOffer,
                    true,
                    getMakerFee(),
                    txFeeFromFeeService,
                    dummyArbitratorAddress);

            final int txSize = tradeFeeTx.bitcoinSerialize().length;
            // use feeTxSizeEstimationRecursionCounter to avoid risk for endless loop
            if (txSize > feeTxSize * 1.2 && feeTxSizeEstimationRecursionCounter < 10) {
                feeTxSizeEstimationRecursionCounter++;
                log.info("txSize is {} bytes but feeTxSize used for txFee calculation was {} bytes. We try again with an " +
                        "adjusted txFee to reach the target tx fee.", txSize, feeTxSize);
                feeTxSize = txSize;
                txFeeFromFeeService = feeService.getTxFee(feeTxSize);
                // lets try again with the adjusted txSize and fee.
                estimateTxSize();
            } else {
                log.info("feeTxSize {} bytes", feeTxSize);
                log.info("txFee based on estimated size: {}, recommended txFee is {} sat/byte",
                        txFeeFromFeeService.toFriendlyString(), feeService.getTxFeePerByte());
            }
        } catch (InsufficientMoneyException e) {
            // If we need to fund from an external wallet we can assume we only have 1 input (260 bytes).
            log.warn("We cannot do the fee estimation because there are not enough funds in the wallet. This is expected " +
                    "if the user pays from an external wallet. In that case we use an estimated tx size of 260 bytes.");
            feeTxSize = 260;
            txFeeFromFeeService = feeService.getTxFee(feeTxSize);
            log.info("feeTxSize {} bytes", feeTxSize);
            log.info("txFee based on estimated size: {}, recommended txFee is {} sat/byte",
                    txFeeFromFeeService.toFriendlyString(), feeService.getTxFeePerByte());
        }
    }

    void onPlaceOffer(Offer offer, TransactionResultHandler resultHandler) {
        checkNotNull(getMakerFee(), "makerFee must not be null");

        Coin reservedFundsForOffer = getSecurityDeposit();
        if (!isBuyOffer())
            reservedFundsForOffer = reservedFundsForOffer.add(amount.get());

        openOfferManager.placeOffer(offer,
                reservedFundsForOffer,
                useSavingsWallet,
                resultHandler,
                log::error);
    }

    void onPaymentAccountSelected(PaymentAccount paymentAccount) {
        if (paymentAccount != null && !this.paymentAccount.equals(paymentAccount)) {
            volume.set(null);
            price.set(null);
            marketPriceMargin = 0;
            preferences.setSelectedPaymentAccountForCreateOffer(paymentAccount);
            this.paymentAccount = paymentAccount;

            setTradeCurrencyFromPaymentAccount(paymentAccount);

            long myLimit = accountAgeWitnessService.getMyTradeLimit(paymentAccount, tradeCurrencyCode.get());
            if (amount.get() != null)
                this.amount.set(Coin.valueOf(Math.min(amount.get().value, myLimit)));
        }
    }

    private void setTradeCurrencyFromPaymentAccount(PaymentAccount paymentAccount) {
        if (paymentAccount.getSelectedTradeCurrency() != null)
            tradeCurrency = paymentAccount.getSelectedTradeCurrency();
        else if (paymentAccount.getSingleTradeCurrency() != null)
            tradeCurrency = paymentAccount.getSingleTradeCurrency();
        else if (!paymentAccount.getTradeCurrencies().isEmpty())
            tradeCurrency = paymentAccount.getTradeCurrencies().get(0);

        checkNotNull(tradeCurrency, "tradeCurrency must not be null");
        tradeCurrencyCode.set(tradeCurrency.getCode());
    }

    void onCurrencySelected(TradeCurrency tradeCurrency) {
        if (tradeCurrency != null) {
            if (!this.tradeCurrency.equals(tradeCurrency)) {
                volume.set(null);
                price.set(null);
                marketPriceMargin = 0;
            }

            this.tradeCurrency = tradeCurrency;
            final String code = this.tradeCurrency.getCode();
            tradeCurrencyCode.set(code);

            if (paymentAccount != null)
                paymentAccount.setSelectedTradeCurrency(tradeCurrency);

            priceFeedService.setCurrencyCode(code);

            Optional<TradeCurrency> tradeCurrencyOptional = preferences.getTradeCurrenciesAsObservable().stream().filter(e -> e.getCode().equals(code)).findAny();
            if (!tradeCurrencyOptional.isPresent()) {
                if (CurrencyUtil.isCryptoCurrency(code)) {
                    CurrencyUtil.getCryptoCurrency(code).ifPresent(preferences::addCryptoCurrency);
                } else {
                    CurrencyUtil.getFiatCurrency(code).ifPresent(preferences::addFiatCurrency);
                }
            }
        }
    }

    @Override
    public void onUpdateBalances(Coin confirmedBalance,
                                 Coin pendingBalance,
                                 Coin lockedForVotingBalance,
                                 Coin lockedInBondsBalance) {
        updateBalance();
    }

    void fundFromSavingsWallet() {
        this.useSavingsWallet = true;
        updateBalance();
        if (!isBtcWalletFunded.get()) {
            this.useSavingsWallet = false;
            updateBalance();
        }
    }

    protected void setMarketPriceMargin(double marketPriceMargin) {
        this.marketPriceMargin = marketPriceMargin;
    }

    void requestTxFee() {
        feeService.requestFees(() -> {
            txFeeFromFeeService = feeService.getTxFee(feeTxSize);
            calculateTotalToPay();
        }, null);
    }

    void setPreferredCurrencyForMakerFeeBtc(boolean preferredCurrencyForMakerFeeBtc) {
        preferences.setPayFeeInBtc(preferredCurrencyForMakerFeeBtc);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean isMinAmountLessOrEqualAmount() {
        //noinspection SimplifiableIfStatement
        if (minAmount.get() != null && amount.get() != null)
            return !minAmount.get().isGreaterThan(amount.get());
        return true;
    }

    OfferPayload.Direction getDirection() {
        return direction;
    }

    AddressEntry getAddressEntry() {
        return addressEntry;
    }

    protected TradeCurrency getTradeCurrency() {
        return tradeCurrency;
    }

    protected PaymentAccount getPaymentAccount() {
        return paymentAccount;
    }

    boolean hasAcceptedArbitrators() {
        final List<Arbitrator> acceptedArbitrators = user.getAcceptedArbitrators();
        return acceptedArbitrators != null && acceptedArbitrators.size() > 0;
    }

    protected void setUseMarketBasedPrice(boolean useMarketBasedPrice) {
        this.useMarketBasedPrice.set(useMarketBasedPrice);
        preferences.setUsePercentageBasedPrice(useMarketBasedPrice);
    }

    /*boolean isFeeFromFundingTxSufficient() {
        return !isMainNet.get() || feeFromFundingTxProperty.get().compareTo(FeePolicy.getMinRequiredFeeForFundingTx()) >= 0;
    }*/

    public ObservableList<PaymentAccount> getPaymentAccounts() {
        return paymentAccounts;
    }

    public double getMarketPriceMargin() {
        return marketPriceMargin;
    }

    boolean isMakerFeeValid() {
        return preferences.getPayFeeInBtc() || isBsqForFeeAvailable();
    }

    long getMaxTradeLimit() {
        if (paymentAccount != null)
            return accountAgeWitnessService.getMyTradeLimit(paymentAccount, tradeCurrencyCode.get());
        else
            return 0;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    void calculateVolume() {
        if (price.get() != null &&
                amount.get() != null &&
                !amount.get().isZero() &&
                !price.get().isZero()) {
            try {
                volume.set(price.get().getVolumeByAmount(amount.get()));
            } catch (Throwable t) {
                log.error(t.toString());
            }
        }

        updateBalance();
    }

    void calculateAmount() {
        if (volume.get() != null &&
                price.get() != null &&
                !volume.get().isZero() &&
                !price.get().isZero()) {
            try {
                amount.set(formatter.reduceTo4Decimals(price.get().getAmountByVolume(volume.get())));
                calculateTotalToPay();
            } catch (Throwable t) {
                log.error(t.toString());
            }
        }
    }

    void calculateTotalToPay() {
        // Maker does not pay the tx fee for the trade txs because the mining fee might be different when maker
        // created the offer and reserved his funds, so that would not work well with dynamic fees.
        // The mining fee for the createOfferFee tx is deducted from the createOfferFee and not visible to the trader
        final Coin makerFee = getMakerFee();
        if (direction != null && amount.get() != null && makerFee != null) {
            Coin feeAndSecDeposit = getTxFee().add(getSecurityDeposit());
            if (isCurrencyForMakerFeeBtc())
                feeAndSecDeposit = feeAndSecDeposit.add(makerFee);
            Coin total = isBuyOffer() ? feeAndSecDeposit : feeAndSecDeposit.add(amount.get());
            totalToPayAsCoin.set(total);
            updateBalance();
        }
    }

    Coin getSecurityDeposit() {
        return isBuyOffer() ? buyerSecurityDeposit.get() : sellerSecurityDeposit;
    }

    public boolean isBuyOffer() {
        return OfferUtil.isBuyOffer(getDirection());
    }

    public Coin getTxFee() {
        if (isCurrencyForMakerFeeBtc())
            return txFeeFromFeeService;
        else
            return txFeeFromFeeService.subtract(getMakerFee());
    }

    public void swapTradeToSavings() {
        btcWalletService.resetAddressEntriesForOpenOffer(offerId);
    }

    private void fillPaymentAccounts() {
        if (user.getPaymentAccounts() != null)
            paymentAccounts.setAll(new HashSet<>(user.getPaymentAccounts()));
    }

    protected void setAmount(Coin amount) {
        this.amount.set(amount);
    }

    protected void setPrice(Price price) {
        this.price.set(price);
    }

    protected void setVolume(Volume volume) {
        this.volume.set(volume);
    }

    void setBuyerSecurityDeposit(Coin buyerSecurityDeposit) {
        this.buyerSecurityDeposit.set(buyerSecurityDeposit);
        preferences.setBuyerSecurityDepositAsLong(buyerSecurityDeposit.value);
    }

    protected boolean isUseMarketBasedPriceValue() {
        return marketPriceAvailable && useMarketBasedPrice.get();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected ReadOnlyObjectProperty<Coin> getAmount() {
        return amount;
    }

    protected ReadOnlyObjectProperty<Coin> getMinAmount() {
        return minAmount;
    }

    public ReadOnlyObjectProperty<Price> getPrice() {
        return price;
    }

    ReadOnlyObjectProperty<Volume> getVolume() {
        return volume;
    }

    protected void setMinAmount(Coin minAmount) {
        this.minAmount.set(minAmount);
    }

    ReadOnlyStringProperty getTradeCurrencyCode() {
        return tradeCurrencyCode;
    }

    ReadOnlyBooleanProperty getUseMarketBasedPrice() {
        return useMarketBasedPrice;
    }

    ReadOnlyObjectProperty<Coin> getBuyerSecurityDeposit() {
        return buyerSecurityDeposit;
    }

    Coin getSellerSecurityDeposit() {
        return sellerSecurityDeposit;
    }

    ReadOnlyObjectProperty<Coin> totalToPayAsCoinProperty() {
        return totalToPayAsCoin;
    }

    public Coin getBsqBalance() {
        return bsqWalletService.getAvailableBalance();
    }

    public void setMarketPriceAvailable(boolean marketPriceAvailable) {
        this.marketPriceAvailable = marketPriceAvailable;
    }

    public Coin getMakerFee(boolean isCurrencyForMakerFeeBtc) {
        return OfferUtil.getMakerFee(isCurrencyForMakerFeeBtc, amount.get(), marketPriceAvailable, marketPriceMargin);
    }

    public Coin getMakerFee() {
        return OfferUtil.getMakerFee(bsqWalletService, preferences, amount.get(), marketPriceAvailable, marketPriceMargin);
    }

    public boolean isCurrencyForMakerFeeBtc() {
        return OfferUtil.isCurrencyForMakerFeeBtc(preferences, bsqWalletService, amount.get(), marketPriceAvailable, marketPriceMargin);
    }

    public boolean isBsqForFeeAvailable() {
        return OfferUtil.isBsqForFeeAvailable(bsqWalletService, amount.get(), marketPriceAvailable, marketPriceMargin);
    }
}
