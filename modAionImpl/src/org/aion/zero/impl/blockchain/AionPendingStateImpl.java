/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 */

package org.aion.zero.impl.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.aion.base.Constant;
import org.aion.base.db.IRepository;
import org.aion.base.db.IRepositoryCache;
import org.aion.base.type.Address;
import org.aion.base.util.ByteUtil;
import org.aion.base.util.FastByteComparisons;
import org.aion.base.util.Hex;
import org.aion.evtmgr.IEvent;
import org.aion.evtmgr.IEventMgr;
import org.aion.evtmgr.IHandler;
import org.aion.evtmgr.impl.callback.EventCallback;
import org.aion.evtmgr.impl.es.EventExecuteService;
import org.aion.evtmgr.impl.evt.EventBlock;
import org.aion.evtmgr.impl.evt.EventTx;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.blockchain.IPendingStateInternal;
import org.aion.mcf.db.TransactionStore;
import org.aion.mcf.evt.IListenerBase.PendingTransactionState;
import org.aion.p2p.INode;
import org.aion.p2p.IP2pMgr;
import org.aion.txpool.ITxPool;
import org.aion.txpool.TxPoolModule;
import org.aion.vm.TransactionExecutor;
import org.aion.zero.impl.AionBlockchainImpl;
import org.aion.zero.impl.config.CfgAion;
import org.aion.zero.impl.core.IAionBlockchain;
import org.aion.zero.impl.db.AionRepositoryImpl;
import org.aion.zero.impl.types.AionBlock;
import org.aion.zero.impl.types.AionTxInfo;
import org.aion.zero.impl.valid.TXValidator;
import org.aion.zero.impl.vm.AionExecutorProvider;
import org.aion.zero.types.AionTransaction;
import org.aion.zero.types.AionTxExecSummary;
import org.aion.zero.types.AionTxReceipt;
import org.aion.zero.types.IAionBlock;
import org.slf4j.Logger;

public class AionPendingStateImpl implements IPendingStateInternal<AionBlock, AionTransaction> {

    public static final Logger LOGGER_TX = AionLoggerFactory.getLogger(LogEnum.TX.toString());
    public static final Logger LOGGER_VM = AionLoggerFactory.getLogger(LogEnum.VM.toString());

    public IP2pMgr p2pMgr;

    public static class TransactionSortedSet extends TreeSet<AionTransaction> {

        public static final long serialVersionUID = 4941385879122799663L;

        public TransactionSortedSet() {
            super((tx1, tx2) -> {
                long nonceDiff = ByteUtil.byteArrayToLong(tx1.getNonce()) -
                    ByteUtil.byteArrayToLong(tx2.getNonce());

                if (nonceDiff != 0) {
                    return nonceDiff > 0 ? 1 : -1;
                }
                return FastByteComparisons.compareTo(tx1.getHash(), 0, 32, tx2.getHash(), 0, 32);
            });
        }
    }

    public static final int MAX_VALIDATED_PENDING_TXS = 8192;

    public final int MAX_TXCACHE_FLUSH_SIZE = MAX_VALIDATED_PENDING_TXS >> 2;

    public IAionBlockchain blockchain;

    public TransactionStore<AionTransaction, AionTxReceipt, AionTxInfo> transactionStore;

    public IRepository repository;

    public ITxPool<AionTransaction> txPool;

    public IEventMgr evtMgr = null;

    public IRepositoryCache pendingState;

    public AtomicReference<AionBlock> best;

    public PendingTxCache pendingTxCache;

    public EventExecuteService ees;

    public List<AionTxExecSummary> txBuffer;

    public boolean bufferEnable;

    public boolean dumpPool;

    public boolean isSeed;

    public boolean loadPendingTx;

    public boolean poolBackUp;

    public Map<byte[], byte[]> backupPendingPoolAdd;
    public Map<byte[], byte[]> backupPendingCacheAdd;
    public Set<byte[]> backupPendingPoolRemove;

    public ScheduledExecutorService ex;

    public boolean closeToNetworkBest = false;

    public static long NRGPRICE_MIN = 10_000_000_000L;  // 10 PLAT  (10 * 10 ^ -9 AION)
    public static long NRGPRICE_MAX = 9_000_000_000_000_000_000L;  //  9 AION

    class TxBuffTask implements Runnable {

        @Override
        public void run() {
            processTxBuffer();
        }
    }

    public synchronized void processTxBuffer() {
        if (!txBuffer.isEmpty()) {
            List<AionTransaction> txs = new ArrayList<>();
            try {
                for (AionTxExecSummary s : txBuffer) {
                    txs.add(s.getTransaction());
                }

                List<AionTransaction> newPending = txPool.add(txs);

                if (LOGGER_TX.isTraceEnabled()) {
                    LOGGER_TX.trace("processTxBuffer buffer#{} poolNewTx#{}", txs.size(),
                        newPending.size());
                }

                int cnt = 0;
                for (AionTxExecSummary summary : txBuffer) {
                    if (newPending.get(cnt) != null && !newPending.get(cnt)
                        .equals(summary.getTransaction())) {
                        AionTxReceipt rp = new AionTxReceipt();
                        rp.setTransaction(newPending.get(cnt));
                        fireTxUpdate(rp, PendingTransactionState.DROPPED, best.get());
                    }
                    cnt++;

                    fireTxUpdate(summary.getReceipt(), PendingTransactionState.NEW_PENDING,
                        best.get());
                }

                if (!txs.isEmpty() && !loadPendingTx) {
                    if (LOGGER_TX.isDebugEnabled()) {
                        LOGGER_TX.debug("processTxBuffer tx#{}", txs.size());
                    }
                    AionImpl.inst().broadcastTransactions(txs);
                }
            } catch (Throwable e) {
                LOGGER_TX.error("processTxBuffer throw {}", e.toString());
            }

            txBuffer.clear();
        }
    }

    public final class EpPS implements Runnable {

        boolean go = true;

        /**
         * When an object implementing interface <code>Runnable</code> is used to create a thread,
         * starting the thread causes the object's
         * <code>run</code> method to be called in that separately executing
         * thread.
         * <p>
         * The general contract of the method <code>run</code> is that it may take any action
         * whatsoever.
         *
         * @see Thread#run()
         */
        @Override
        public void run() {
            while (go) {
                IEvent e = ees.take();

                if (e.getEventType() == IHandler.TYPE.BLOCK0.getValue()
                    && e.getCallbackType() == EventBlock.CALLBACK.ONBEST0.getValue()) {
                    long t1 = System.currentTimeMillis();
                    processBest((AionBlock) e.getFuncArgs().get(0), (List) e.getFuncArgs().get(1));

                    if (LOGGER_TX.isDebugEnabled()) {
                        long t2 = System.currentTimeMillis();
                        LOGGER_TX.debug("Pending state update took {} ms", t2 - t1);
                    }
                } else if (e.getEventType() == IHandler.TYPE.TX0.getValue()
                    && e.getCallbackType() == EventTx.CALLBACK.TXBACKUP0.getValue()) {
                    long t1 = System.currentTimeMillis();
                    backupPendingTx();

                    if (LOGGER_TX.isDebugEnabled()) {
                        long t2 = System.currentTimeMillis();
                        LOGGER_TX.debug("Pending state backupPending took {} ms", t2 - t1);
                    }
                } else if (e.getEventType() == IHandler.TYPE.POISONPILL.getValue()) {
                    go = false;
                }
            }
        }
    }

    public synchronized void backupPendingTx() {

        if (!backupPendingPoolAdd.isEmpty()) {
            repository.addTxBatch(backupPendingPoolAdd, true);
        }

        if (!backupPendingCacheAdd.isEmpty()) {
            repository.addTxBatch(backupPendingCacheAdd, false);
        }

        if (!backupPendingPoolRemove.isEmpty()) {
            repository.removeTxBatch(backupPendingPoolRemove, true);
        }

        repository.removeTxBatch(pendingTxCache.getClearTxHash(), false);
        repository.flush();

        backupPendingPoolAdd.clear();
        backupPendingCacheAdd.clear();
        backupPendingPoolRemove.clear();
        pendingTxCache.clearCacheTxHash();

    }

    public static AionPendingStateImpl initializeAionPendingState() {
        AionPendingStateImpl ps = new AionPendingStateImpl(AionRepositoryImpl.inst());
        ps.init(AionBlockchainImpl.inst());
        return ps;
    }

    public static class Holder {

        static final AionPendingStateImpl INSTANCE = initializeAionPendingState();
    }

    public static AionPendingStateImpl inst() {
        return Holder.INSTANCE;
    }

    public AionPendingStateImpl(AionRepositoryImpl repository) {
        this.repository = repository;

        this.isSeed = CfgAion.inst().getConsensus().isSeed();

        if (!isSeed) {

            try {
                ServiceLoader.load(TxPoolModule.class);
            } catch (Exception e) {
                LOGGER_TX.error("load TxPoolModule service fail!", e);
                throw e;
            }

            Properties prop = new Properties();

            prop.put(TxPoolModule.MODULENAME, "org.aion.txpool.zero.TxPoolA0");
            // The BlockEnergyLimit will be updated when the best block found.
            prop.put(ITxPool.PROP_BLOCK_NRG_LIMIT,
                String.valueOf(CfgAion.inst().getConsensus().getEnergyStrategy().getUpperBound()));
            prop.put(ITxPool.PROP_BLOCK_SIZE_LIMIT, String.valueOf(Constant.MAX_BLK_SIZE));
            prop.put(ITxPool.PROP_TX_TIMEOUT, "86400");
            TxPoolModule txPoolModule;
            try {
                txPoolModule = TxPoolModule.getSingleton(prop);
                //noinspection unchecked
                this.txPool = (ITxPool<AionTransaction>) txPoolModule.getTxPool();
            } catch (Throwable e) {
                LOGGER_TX.error("TxPoolModule getTxPool fail!", e);
            }

        } else {
            LOGGER_TX.info("Seed mode is enable");
        }
    }

    public void init(final AionBlockchainImpl blockchain) {
        if (!this.isSeed) {
            this.blockchain = blockchain;
            this.best = new AtomicReference<>();
            this.transactionStore = blockchain.getTransactionStore();

            this.evtMgr = blockchain.getEventMgr();
            this.poolBackUp = CfgAion.inst().getTx().getPoolBackup();
            this.pendingTxCache = new PendingTxCache(CfgAion.inst().getTx().getCacheMax(),
                poolBackUp);
            this.pendingState = repository.startTracking();

            this.dumpPool = CfgAion.inst().getTx().getPoolDump();

            ees = new EventExecuteService(1000, "EpPS", Thread.MAX_PRIORITY, LOGGER_TX);
            ees.setFilter(setEvtFilter());

            regBlockEvents();

            IHandler blkHandler = this.evtMgr.getHandler(IHandler.TYPE.BLOCK0.getValue());
            if (blkHandler != null) {
                blkHandler.eventCallback(new EventCallback(ees, LOGGER_TX));
            }

            if (poolBackUp) {
                this.backupPendingPoolAdd = new HashMap<>();
                this.backupPendingCacheAdd = new HashMap<>();
                this.backupPendingPoolRemove = new HashSet<>();

                regTxEvents();
                IHandler txHandler = this.evtMgr.getHandler(IHandler.TYPE.TX0.getValue());
                if (txHandler != null) {
                    txHandler.eventCallback(new EventCallback(ees, LOGGER_TX));
                }
            }

            this.bufferEnable = CfgAion.inst().getTx().getBuffer();
            if (bufferEnable) {
                LOGGER_TX.info("TxBuf enable!");
                this.ex = Executors.newSingleThreadScheduledExecutor();
                this.ex.scheduleWithFixedDelay(new TxBuffTask(), 5000, 500, TimeUnit.MILLISECONDS);

                this.txBuffer = Collections.synchronizedList(new ArrayList<>());
            }

            ees.start(new EpPS());
        }

    }

    /**
     * Produces a set of integers representing events that an EventExecuteService will use to filter
     * out events. Essentially, the EventExecuteService will only add events that are one of the
     * following events specified by the filter.
     *
     * This filter adds an event filter for BLOCK0/ONBEST0
     * If there is a backup pool then an event filter for TX0/TXBACKUP0 is also added.
     *
     * @return A set of numeric filters.
     */
    public Set<Integer> setEvtFilter() {
        Set<Integer> eventSN = new HashSet<>();

        int sn = IHandler.TYPE.BLOCK0.getValue() << 8;
        eventSN.add(sn + EventBlock.CALLBACK.ONBEST0.getValue());

        if (poolBackUp) {
            sn = IHandler.TYPE.TX0.getValue() << 8;
            eventSN.add(sn + EventTx.CALLBACK.TXBACKUP0.getValue());
        }

        return eventSN;
    }

    /**
     * Adds the following two block events to the event manager: ONBLOCK0, ONBEST0.
     */
    public void regBlockEvents() {
        List<IEvent> evts = new ArrayList<>();
        evts.add(new EventBlock(EventBlock.CALLBACK.ONBLOCK0));
        evts.add(new EventBlock(EventBlock.CALLBACK.ONBEST0));

        this.evtMgr.registerEvent(evts);
    }

    /**
     * Adds the following transaction event to the event manager: TXBACKUP0.
     */
    public void regTxEvents() {
        List<IEvent> evts = new ArrayList<>();
        evts.add(new EventTx(EventTx.CALLBACK.TXBACKUP0));

        this.evtMgr.registerEvent(evts);
    }

    /**
     * Returns the repository's tracker.
     *
     * @return the repository's tracker.
     */
    @Override
    public synchronized IRepositoryCache<?, ?, ?> getRepository() {
        //TODO: why is this synchronized? it's just a reference
        // Todo : no class use this method.
        return pendingState;
    }

    /**
     * Returns the number of pending transactions.
     *
     * @return the number of pending transactions.
     */
    public int getPendingTxSize() {
        return isSeed ? 0 : this.txPool.size();
    }

    /**
     * Returns a list of all the pending transactions.
     *
     * @return a list of all the pending transactions.
     */
    @Override
    public synchronized List<AionTransaction> getPendingTransactions() {
        return isSeed ? new ArrayList<>() : this.txPool.snapshot();

    }

    /**
     * Returns the current best block in the blockchain.
     *
     * @return The best block.
     */
    public synchronized AionBlock getBestBlock() {
        best.set(blockchain.getBestBlock());
        return best.get();
    }

    /**
     * TODO: when we removed libNc, timers were not introduced yet, we must rework the model that
     * libAion uses to work with timers
     */
    @Override
    public synchronized List<AionTransaction> addPendingTransaction(AionTransaction tx) {

        return addPendingTransactions(Collections.singletonList(tx));
    }

    @Override
    public synchronized List<AionTransaction> addPendingTransactions(
        List<AionTransaction> transactions) {

        //FIXME: looks like with some refactorings we can remove sync keyword on method and it will
        //FIXME: only be needed as sync'd blocks in 1 or 2 places inside this method.
        if ((isSeed || !closeToNetworkBest) && !loadPendingTx) {
            return seedProcess(transactions);                           //TODO: seems thread-safe
        } else {
            List<AionTransaction> newPending = new ArrayList<>();
            List<AionTransaction> newLargeNonceTx = new ArrayList<>();

            for (AionTransaction tx : transactions) {
                BigInteger txNonce = tx.getNonceBI();
                BigInteger bestPSNonce = bestPendingStateNonce(tx.getFrom());   // this call is sync'd

                int cmp = txNonce.compareTo(bestPSNonce);

                if (cmp > 0) { // may be able to get this if-block unsync'd
                    if (!isInTxCache(tx.getFrom(), tx.getNonceBI())) {      //TODO: try sync this method - necessary?
                        newLargeNonceTx.add(tx);
                        addToTxCache(tx);                                   //TODO: try sync this method - necessary?

                        if (poolBackUp) {
                            backupPendingCacheAdd.put(tx.getHash(), tx.getEncoded());   //TODO: use concurrent map
                        }

                        if (LOGGER_TX.isTraceEnabled()) {
                            LOGGER_TX.trace(
                                "addPendingTransactions addToCache due to largeNonce: from = {}, nonce = {}",
                                tx.getFrom(), txNonce);
                        }
                    }
                } else if (cmp == 0) {
                    if (txPool.size() > MAX_VALIDATED_PENDING_TXS) {

                        if (!isInTxCache(tx.getFrom(), tx.getNonceBI())) {      // see above.
                            newLargeNonceTx.add(tx);
                            addToTxCache(tx);                                   // see above.

                            if (poolBackUp) {
                                backupPendingCacheAdd.put(tx.getHash(), tx.getEncoded());   // see above.
                            }

                            if (LOGGER_TX.isTraceEnabled()) {
                                LOGGER_TX.trace(
                                    "addPendingTransactions addToCache due to poolMax: from = {}, nonce = {}",
                                    tx.getFrom(), txNonce);
                            }
                        }

                        continue;
                    }

                    // TODO: need to implement better cache return Strategy
                    // the commons LRUmap could be wrapped in Collections.synchronizedMap(..) or
                    // consider Guava's cache as:
                    // ConcurrentMap cache = CacheBuilder.newBuilder().maxSize().build().asMap()
                    // if that class is non-thread-safe may have to sync here.
                    Map<BigInteger, AionTransaction> cache = pendingTxCache.geCacheTx(tx.getFrom());

                    int limit = 0;
                    Set<Address> addr = pendingTxCache.getCacheTxAccount(); // see above.
                    if (!addr.isEmpty()) {
                        limit = MAX_TXCACHE_FLUSH_SIZE / addr.size();

                        if (limit == 0) {
                            limit = 1;
                        }
                    }

                    if (LOGGER_TX.isTraceEnabled()) {
                        LOGGER_TX.trace("addPendingTransactions from cache: from {}, size {}",
                            tx.getFrom(), cache.size());
                    }

                    do {
                        if (addPendingTransactionImpl(tx, txNonce)) {   //TODO: inspect; may have to sync here
                            newPending.add(tx);

                            if (poolBackUp) {
                                backupPendingPoolAdd.put(tx.getHash(), tx.getEncoded());    //TODO: sync this map
                            }
                        } else {
                            break;
                        }

                        if (LOGGER_TX.isTraceEnabled()) {
                            LOGGER_TX.trace("cache: from {}, nonce {}", tx.getFrom(),
                                txNonce.toString());
                        }

                        txNonce = txNonce.add(BigInteger.ONE);
                    } while (cache != null &&
                        (tx = cache.get(txNonce)) != null &&
                        (limit-- > 0) &&                      // decrements after evaluation - intended??
                        (txBuffer == null ? txPool.size() : txPool.size() + txBuffer.size())
                            < MAX_VALIDATED_PENDING_TXS);
                } else if (bestRepoNonce(tx.getFrom()).compareTo(txNonce) < 1) {    //TODO: sync this method?
                    // repay Tx
                    if (addPendingTransactionImpl(tx, txNonce)) {   // see above
                        newPending.add(tx);

                        if (poolBackUp) {   // how does a variable like this change throughout lifetime?
                            backupPendingPoolAdd.put(tx.getHash(), tx.getEncoded());    // see above
                        }
                    }
                }
            }

            if (LOGGER_TX.isTraceEnabled()) {
                LOGGER_TX.trace(
                    "Wire transaction list added: total: {}, newPending: {}, cached: {}, valid (added to pending): {} pool_size:{}",
                    transactions.size(), newPending, newLargeNonceTx.size(), txPool.size());
            }

            if (!newPending.isEmpty()) {
                IEvent evtRecv = new EventTx(EventTx.CALLBACK.PENDINGTXRECEIVED0);
                evtRecv.setFuncArgs(Collections.singletonList(newPending));
                this.evtMgr.newEvent(evtRecv);  // seems to be thread-safe? hard to tell - inspect

                IEvent evtChange = new EventTx(EventTx.CALLBACK.PENDINGTXSTATECHANGE0);
                this.evtMgr.newEvent(evtChange);    // see previous
            }

            if (!loadPendingTx) {
                if (bufferEnable) {
                    if (!newLargeNonceTx.isEmpty()) {
                        AionImpl.inst().broadcastTransactions(newLargeNonceTx); // seems thread-safe
                    }
                } else {
                    if (!newPending.isEmpty() || !newLargeNonceTx.isEmpty()) {
                        AionImpl.inst().broadcastTransactions(                  // seems thread-safe
                            Stream.concat(newPending.stream(), newLargeNonceTx.stream())
                                .collect(Collectors.toList()));
                    }
                }
            }

            return newPending;
        }
    }

    /**
     * Broadcasts each valid transaction in transactions and returns the list of all such valid and
     * broadcasted transactions.
     *
     * @param transactions A list of transactions to process.
     * @return The list of all valid and broadcasted transactions.
     */
    public List<AionTransaction> seedProcess(List<AionTransaction> transactions) {
        //TODO: looks like this method is thread-safe -- inspect to ensure
        List<AionTransaction> newTx = new ArrayList<>();
        for (AionTransaction tx : transactions) {
            if (TXValidator.isValid(tx)) {
                newTx.add(tx);
            } else {
                LOGGER_TX
                    .error("tx sig does not match with the tx raw data, tx[{}]", tx.toString());
            }
        }

        if (!newTx.isEmpty()) {
            AionImpl.inst().broadcastTransactions(newTx);
        }

        return newTx;
    }

    public boolean inPool(BigInteger txNonce, Address from) {
        //TODO: thread-safe from what I can tell
        return (this.txPool.bestPoolNonce(from).compareTo(txNonce) > -1);
    }

    public void fireTxUpdate(AionTxReceipt txReceipt, PendingTransactionState state,
        IAionBlock block) {
        //TODO: thread-safe so long as we can trust our input objects
        if (LOGGER_TX.isTraceEnabled()) {
            LOGGER_TX.trace(String
                .format("PendingTransactionUpdate: (Tot: %3s) %12s : %s %8s %s [%s]",
                    getPendingTxSize(),
                    state, txReceipt.getTransaction().getFrom().toString().substring(0, 8),
                    ByteUtil.byteArrayToLong(txReceipt.getTransaction().getNonce()),
                    block.getShortDescr(),
                    txReceipt.getError()));
        }

        IEvent evt = new EventTx(EventTx.CALLBACK.PENDINGTXUPDATE0);
        List<Object> args = new ArrayList<>();
        args.add(txReceipt);
        args.add(state.getValue());
        args.add(block);
        evt.setFuncArgs(args);
        this.evtMgr.newEvent(evt);
    }

    /**
     * Executes pending tx on the latest best block Fires pending state update
     *
     * @param tx transaction come from API or P2P
     * @param txNonce nonce of the transaction.
     * @return True if transaction gets NEW_PENDING state, False if DROPPED
     */
    public boolean addPendingTransactionImpl(final AionTransaction tx, BigInteger txNonce) {
        //FIXME: the final keyword is a bit misleading for tx, we do modify it in this method
        //FIXME: may need to have a sync block in 1 or 2 places.
        if (!TXValidator.isValid(tx)) {         // this method is thread-safe
            LOGGER_TX.error("invalid Tx [{}]", tx.toString());
            fireDroppedTx(tx, "INVALID_TX");    // seems thread-safe
            return false;
        }

        if (inValidTxNrgPrice(tx)) {        // seems thread-safe
            LOGGER_TX.error("invalid Tx Nrg price [{}]", tx.toString());
            fireDroppedTx(tx, "INVALID_TX_NRG_PRICE");  // see above
            return false;
        }

        AionTxExecSummary txSum;
        boolean ip = inPool(txNonce, tx.getFrom());     // seems thread-safe
        if (ip) {
            // check energy usage
            AionTransaction poolTx = txPool.getPoolTx(tx.getFrom(), txNonce);   // method is sync'd but hold ref carefully
            if (poolTx == null) {
                LOGGER_TX.error("addPendingTransactionImpl no same tx nonce in the pool {}",
                    tx.toString());
                fireDroppedTx(tx, "REPAYTX_POOL_EXCEPTION");    // see above
                return false;
            } else {
                long price = (poolTx.getNrgPrice() << 1);   // thread-safe? how does the rlp call behave?
                if (price > 0 && price <= tx.getNrgPrice()) {
                    txSum = executeTx(tx, true);
                } else {
                    fireDroppedTx(tx, "REPAYTX_LOWPRICE");
                    return false;
                }
            }
        } else {
            txSum = executeTx(tx, false);           // should be thread-safe
        }

        if (txSum.isRejected()) {   // only we hold this ref, all good
            if (LOGGER_TX.isTraceEnabled()) {
                LOGGER_TX.trace("addPendingTransactionImpl tx is rejected due to: {}",
                    txSum.getReceipt().getError());
            }
            fireTxUpdate(txSum.getReceipt(), PendingTransactionState.DROPPED, best.get());  // should be safe
            return false;
        } else {
            tx.setNrgConsume(txSum.getReceipt().getEnergyUsed());   // modifies tx; safe only if we can trust this input

            if (LOGGER_TX.isTraceEnabled()) {
                LOGGER_TX.trace("addPendingTransactionImpl validTx {}", tx.toString());
            }

            if (bufferEnable) {
                txBuffer.add(txSum);    // this method is sync'd
            } else {
                AionTransaction rtn = this.txPool.add(tx);  //TODO: may need to be sync'd
                if (rtn != null && !rtn.equals(tx)) {
                    AionTxReceipt rp = new AionTxReceipt();
                    rp.setTransaction(rtn);

                    if (poolBackUp) {
                        backupPendingPoolRemove.add(tx.getHash().clone());  // need to sync this set
                    }
                    fireTxUpdate(rp, PendingTransactionState.DROPPED, best.get());  // see above
                }

                fireTxUpdate(txSum.getReceipt(), PendingTransactionState.NEW_PENDING, best.get());  // see above
            }

            return true;
        }
    }

    public boolean inValidTxNrgPrice(AionTransaction tx) {
        //TODO: should be thread-safe; better if we can finalize the nrg fields of tx
        return tx.getNrgPrice() < NRGPRICE_MIN || tx.getNrgPrice() > NRGPRICE_MAX;
    }

    public void fireDroppedTx(AionTransaction tx, String error) {
        //TODO: thread-safe so long as we can trust our inputs
        if (LOGGER_TX.isErrorEnabled()) {
            LOGGER_TX.error("Tx dropped {} [{}]", error, tx.toString());
        }

        AionTxReceipt rp = new AionTxReceipt();
        rp.setTransaction(tx);
        rp.setError(error);
        fireTxUpdate(rp, PendingTransactionState.DROPPED, best.get());
    }

    public AionTxReceipt createDroppedReceipt(AionTransaction tx, String error) {
        //TODO: thread-safe if we can trust our inputs
        AionTxReceipt txReceipt = new AionTxReceipt();
        txReceipt.setTransaction(tx);
        txReceipt.setError(error);
        return txReceipt;
    }

    /**
     * Returns the first block that is an ancestor of both b1 and b2.
     *
     * @param b1 A block.
     * @param b2 A block.
     * @return The first common ancestor of b1 and b2.
     */
    public IAionBlock findCommonAncestor(IAionBlock b1, IAionBlock b2) {
        //TODO: the blockchain should be thread-safe... check
        while (!b1.isEqual(b2)) {
            if (b1.getNumber() >= b2.getNumber()) {
                b1 = blockchain.getBlockByHash(b1.getParentHash());
            }

            if (b1.getNumber() < b2.getNumber()) {
                b2 = blockchain.getBlockByHash(b2.getParentHash());
            }
            if (b2 == null) {
                // shouldn't happen
                throw new RuntimeException(
                    "Pending state can't find common ancestor: one of blocks has a gap");
            }
        }
        return b1;
    }

    @Override
    public synchronized void processBest(AionBlock newBlock, List receipts) {
        //TODO: may be possible to get a synchronized block to do the work, much more unsafe stuff in here though
        if (isSeed) {
            // seed mode doesn't need to update the pendingState
            return;
        }

        if (best.get() != null && !best.get().isParentOf(newBlock)) {

            // need to switch the state to another fork

            IAionBlock commonAncestor = findCommonAncestor(best.get(), newBlock);   // see method

            if (LOGGER_TX.isDebugEnabled()) {
                LOGGER_TX.debug(
                    "New best block from another fork: " + newBlock.getShortDescr() + ", old best: "
                        + best.get()
                        .getShortDescr() + ", ancestor: " + commonAncestor.getShortDescr());
            }

            // first return back the transactions from forked blocks
            IAionBlock rollback = best.get();
            while (!rollback.isEqual(commonAncestor)) {
                if (LOGGER_TX.isDebugEnabled()) {
                    LOGGER_TX.debug("Rollback: {}", rollback.getShortDescr());
                }
                List<AionTransaction> atl = rollback.getTransactionsList(); // not thread-safe?
                if (!atl.isEmpty()) {
                    this.txPool.add(atl);
                }
                rollback = blockchain.getBlockByHash(rollback.getParentHash()); // should be thread-safe
            }

            // rollback the state snapshot to the ancestor
            pendingState = repository.getSnapshotTo(commonAncestor.getStateRoot()).startTracking(); // not safe?

            // next process blocks from new fork
            IAionBlock main = newBlock;
            List<IAionBlock> mainFork = new ArrayList<>();
            while (!main.isEqual(commonAncestor)) {
                if (LOGGER_TX.isDebugEnabled()) {
                    LOGGER_TX.debug("Mainfork: {}", main.getShortDescr());
                }

                mainFork.add(main);
                main = blockchain.getBlockByHash(main.getParentHash()); // should be safe?
            }

            // processing blocks from ancestor to new block
            for (int i = mainFork.size() - 1; i >= 0; i--) {
                processBestInternal(mainFork.get(i), null);
            }
        } else {
            if (LOGGER_TX.isDebugEnabled()) {
                LOGGER_TX.debug("PendingStateImpl.processBest: " + newBlock.getShortDescr());
            }
            //noinspection unchecked
            processBestInternal(newBlock, receipts);        // potentially safe, prob not.
        }

        best.set(newBlock);

        closeToNetworkBest = best.get().getNumber() + 128 >= getPeersBestBlk13();   // safe

        if (LOGGER_TX.isDebugEnabled()) {
            LOGGER_TX
                .debug("PendingStateImpl.processBest: closeToNetworkBest[{}]", closeToNetworkBest);
        }

        updateState(best.get());    // safe

        txPool.updateBlkNrgLimit(best.get().getNrgLimit());

        flushCachePendingTx();  // potential to be safe; may be hard

        List<IEvent> events = new ArrayList<>();
        events.add(new EventTx(EventTx.CALLBACK.PENDINGTXSTATECHANGE0));

        if (poolBackUp) {
            events.add(new EventTx(EventTx.CALLBACK.TXBACKUP0));
        }

        this.evtMgr.newEvents(events);

        // This is for debug purpose, do not use in the regular kernel running.
        if (this.dumpPool) {
            DumpPool();
        }
    }

    public void flushCachePendingTx() {
        //TODO: method has potential to not need syncing, maybe not possible though
        Set<Address> cacheTxAccount = this.pendingTxCache.getCacheTxAccount();  // can use thread-safe obj?

        if (cacheTxAccount.isEmpty()) {
            return;
        }

        if (LOGGER_TX.isDebugEnabled()) {
            LOGGER_TX
                .debug("PendingStateImpl.flushCachePendingTx: acc#[{}]", cacheTxAccount.size());
        }

        Map<Address, BigInteger> nonceMap = new HashMap<>();
        for (Address addr : cacheTxAccount) {
            nonceMap.put(addr, bestPendingStateNonce(addr));    // thread-safe
        }

        List<AionTransaction> newPendingTx = this.pendingTxCache.flush(nonceMap);   // not fully safe

        if (LOGGER_TX.isDebugEnabled()) {
            LOGGER_TX
                .debug("PendingStateImpl.flushCachePendingTx: newPendingTx_size[{}]",
                    newPendingTx.size());
        }

        if (!newPendingTx.isEmpty()) {
            addPendingTransactions(newPendingTx);   // should be safe
        }
    }

    public void processBestInternal(IAionBlock block, List<AionTxReceipt> receipts) {
        //TODO: thread-safety dependent on these methods -- potentially safe.
        clearPending(block, receipts);

        clearOutdated(block.getNumber());
    }

    public void clearOutdated(final long blockNumber) {
        //TODO: good potential to not need syncing.
        List<AionTransaction> outdated = new ArrayList<>();

        final long timeout = this.txPool.getOutDateTime();          // safe
        for (AionTransaction tx : this.txPool.getOutdatedList()) {  // not thread-safe technically.
            outdated.add(tx);

            if (poolBackUp) {
                backupPendingPoolRemove.add(tx.getHash().clone());  // make thread-safe
            }
            // @Jay
            // TODO : considering add new state - TIMEOUT
            fireTxUpdate(
                createDroppedReceipt(tx, "Tx was not included into last " + timeout + " seconds"),
                PendingTransactionState.DROPPED, best.get());
        }

        if (LOGGER_TX.isDebugEnabled()) {
            LOGGER_TX.debug("clearOutdated block#[{}] tx#[{}]", blockNumber, outdated.size());
        }

        if (outdated.isEmpty()) {
            return;
        }

        txPool.remove(outdated);
    }

    @SuppressWarnings("unchecked")
    public void clearPending(IAionBlock block, List<AionTxReceipt> receipts) {

        if (block.getTransactionsList() != null) {
            if (LOGGER_TX.isDebugEnabled()) {
                LOGGER_TX.debug("clearPending block#[{}] tx#[{}]", block.getNumber(),
                    block.getTransactionsList().size());
            }

            Map<Address, BigInteger> accountNonce = new HashMap<>();
            int cnt = 0;
            for (AionTransaction tx : block.getTransactionsList()) {
                accountNonce
                    .computeIfAbsent(tx.getFrom(), k -> this.repository.getNonce(tx.getFrom()));

                if (LOGGER_TX.isTraceEnabled()) {
                    LOGGER_TX.trace("Clear pending transaction, addr: {} hash: {}",
                        tx.getFrom().toString(),
                        Hex.toHexString(tx.getHash()));
                }

                AionTxReceipt receipt;
                if (receipts != null) {
                    receipt = receipts.get(cnt);
                } else {
                    AionTxInfo info = getTransactionInfo(tx.getHash(), block.getHash());
                    receipt = info.getReceipt();
                }

                if (poolBackUp) {
                    backupPendingPoolRemove.add(tx.getHash().clone());
                }
                fireTxUpdate(receipt, PendingTransactionState.INCLUDED, block);
                cnt++;
            }

            if (!accountNonce.isEmpty()) {
                this.txPool.remove(accountNonce);
            }
        }
    }

    public AionTxInfo getTransactionInfo(byte[] txHash, byte[] blockHash) {
        AionTxInfo info = transactionStore.get(txHash, blockHash);
        AionTransaction tx = blockchain.getBlockByHash(info.getBlockHash()).getTransactionsList()
            .get(info.getIndex());
        info.getReceipt().setTransaction(tx);
        return info;
    }

    @SuppressWarnings("UnusedReturnValue")
    public List<AionTransaction> updateState(IAionBlock block) {

        pendingState = repository.startTracking();

        processTxBuffer();
        List<AionTransaction> pendingTxl = this.txPool.snapshotAll();
        List<AionTransaction> rtn = new ArrayList<>();
        if (LOGGER_TX.isInfoEnabled()) {
            LOGGER_TX.info("updateState - snapshotAll tx[{}]", pendingTxl.size());
        }
        for (AionTransaction tx : pendingTxl) {
            if (LOGGER_TX.isTraceEnabled()) {
                LOGGER_TX.trace("updateState - loop: " + tx.toString());
            }

            AionTxExecSummary txSum = executeTx(tx, false);
            AionTxReceipt receipt = txSum.getReceipt();
            receipt.setTransaction(tx);

            if (txSum.isRejected()) {
                if (LOGGER_TX.isDebugEnabled()) {
                    LOGGER_TX.debug("Invalid transaction in txpool: {}", tx);
                }
                txPool.remove(Collections.singletonList(tx));

                if (poolBackUp) {
                    backupPendingPoolRemove.add(tx.getHash().clone());
                }
                fireTxUpdate(receipt, PendingTransactionState.DROPPED, block);
            } else {
                fireTxUpdate(receipt, PendingTransactionState.PENDING, block);
                rtn.add(tx);
            }
        }

        return rtn;
    }

    public Set<Address> getTxsAccounts(List<AionTransaction> txn) {
        Set<Address> rtn = new HashSet<>();
        for (AionTransaction tx : txn) {
            rtn.add(tx.getFrom());
        }
        return rtn;
    }

    public AionTxExecSummary executeTx(AionTransaction tx, boolean inPool) {
        //TODO: should be thread-safe
        IAionBlock bestBlk = best.get();
        if (LOGGER_TX.isTraceEnabled()) {
            LOGGER_TX.trace("executeTx: {}", Hex.toHexString(tx.getHash()));
        }

        TransactionExecutor txExe = new TransactionExecutor(tx, bestBlk, pendingState,
            LOGGER_VM);
        txExe.setExecutorProvider(AionExecutorProvider.getInstance());  // thread-safe

        if (inPool) {
            txExe.setBypassNonce();
        }

        return txExe.execute();
    }

    @Override
    public synchronized BigInteger bestPendingStateNonce(Address addr) {
        //TODO: thread-safe
        return isSeed ? BigInteger.ZERO : this.pendingState.getNonce(addr);
    }

    public BigInteger bestRepoNonce(Address addr) {
        return this.repository.getNonce(addr);
    }

    public void addToTxCache(AionTransaction tx) {
        this.pendingTxCache.addCacheTx(tx);
    }

    public boolean isInTxCache(Address addr, BigInteger nonce) {
        return this.pendingTxCache.isInCache(addr, nonce);
    }

    @Override
    public void shutDown() {
        if (this.bufferEnable) {
            ex.shutdown();
        }

        if (ees != null) {
            ees.shutdown();
        }
    }

    @Override
    public synchronized void DumpPool() {
        //TODO: exit if log level inappropriate
        List<AionTransaction> txn = txPool.snapshotAll();
        Set<Address> addrs = new HashSet<>();
        LOGGER_TX.info("");
        LOGGER_TX.info("=========== SnapshotAll");
        for (AionTransaction tx : txn) {
            addrs.add(tx.getFrom());
            LOGGER_TX.info("{}", tx.toString());
        }

        txn = txPool.snapshot();
        LOGGER_TX.info("");
        LOGGER_TX.info("=========== Snapshot");
        for (AionTransaction tx : txn) {
            LOGGER_TX.info("{}", tx.toString());
        }

        LOGGER_TX.info("");
        LOGGER_TX.info("=========== Pool best nonce");
        for (Address addr : addrs) {
            LOGGER_TX.info("{} {}", addr.toString(), txPool.bestPoolNonce(addr));
        }

        LOGGER_TX.info("");
        LOGGER_TX.info("=========== Cache pending tx");
        Set<Address> cacheAddr = pendingTxCache.getCacheTxAccount();
        for (Address addr : cacheAddr) {
            Map<BigInteger, AionTransaction> cacheMap = pendingTxCache.geCacheTx(addr);
            if (cacheMap != null) {
                for (AionTransaction tx : cacheMap.values()) {
                    LOGGER_TX.info("{}", tx.toString());
                }
            }
        }

        LOGGER_TX.info("");
        LOGGER_TX.info("=========== db nonce");
        addrs.addAll(cacheAddr);
        for (Address addr : addrs) {
            LOGGER_TX.info("{} {}", addr.toString(), bestRepoNonce(addr));
        }

        LOGGER_TX.info("");
        LOGGER_TX.info("=========== ps nonce");
        addrs.addAll(cacheAddr);
        for (Address addr : addrs) {
            LOGGER_TX.info("{} {}", addr.toString(), bestPendingStateNonce(addr));
        }
    }

    @Override
    public void loadPendingTx() {

        loadPendingTx = true;
        recoverPool();
        recoverCache();
        loadPendingTx = false;

    }

    public void setP2pMgr(final IP2pMgr p2pMgr) {
        if (!this.isSeed) {
            this.p2pMgr = p2pMgr;
        }
    }

    public long getPeersBestBlk13() {
        //TODO: this method is thread safe.
        if (this.p2pMgr == null) {
            return 0;
        }

        List<Long> peersBest = new ArrayList<>();
        for (INode node : p2pMgr.getActiveNodes().values()) {   // method is safe
            peersBest.add(node.getBestBlockNumber());
        }

        if (peersBest.isEmpty()) {
            return 0;
        }

        peersBest.sort(Comparator.reverseOrder());

        int position = peersBest.size() / 3;
        if (position > 3) {
            position -= 1;
        }

        if (LOGGER_TX.isDebugEnabled()) {
            StringBuilder blk = new StringBuilder();
            for (Long l : peersBest) {
                blk.append(l.toString()).append(" ");
            }

            LOGGER_TX.debug("getPeersBestBlk13 peers[{}] 1/3[{}] PeersBest[{}]", peersBest.size(),
                peersBest.get(position), blk.toString());
        }

        return peersBest.get(position);
    }

    /**
     * Recovers the transaction cache from the repository.
     */
    public void recoverCache() {

        LOGGER_TX.info("pendingCacheTx loading from DB");
        long t1 = System.currentTimeMillis();
        //noinspection unchecked
        List<byte[]> pendingCacheTxBytes = repository.getCacheTx();

        List<AionTransaction> pendingTx = new ArrayList<>();
        for (byte[] b : pendingCacheTxBytes) {
            try {
                pendingTx.add(new AionTransaction(b));
            } catch (Throwable e) {
                LOGGER_TX.error("loadingPendingCacheTx error {}", e.toString());
            }
        }

        Map<Address, SortedMap<BigInteger, AionTransaction>> sortedMap = new HashMap<>();
        for (AionTransaction tx : pendingTx) {
            if (sortedMap.get(tx.getFrom()) == null) {
                SortedMap<BigInteger, AionTransaction> accountSortedMap = new TreeMap<>();
                accountSortedMap.put(tx.getNonceBI(), tx);

                sortedMap.put(tx.getFrom(), accountSortedMap);
            } else {
                sortedMap.get(tx.getFrom()).put(tx.getNonceBI(), tx);
            }
        }

        int cnt = 0;
        for (Map.Entry<Address, SortedMap<BigInteger, AionTransaction>> e : sortedMap.entrySet()) {
            for (AionTransaction tx : e.getValue().values()) {
                pendingTxCache.addCacheTx(tx);
                cnt++;
            }
        }

        long t2 = System.currentTimeMillis() - t1;
        LOGGER_TX.info("{} pendingCacheTx loaded from DB into the pendingCache, {} ms", cnt, t2);
    }

    /**
     * Recovers the transaction pool from the repository.
     */
    public void recoverPool() {

        LOGGER_TX.info("pendingPoolTx loading from DB");
        long t1 = System.currentTimeMillis();
        //noinspection unchecked
        List<byte[]> pendingPoolTxBytes = repository.getPoolTx();

        List<AionTransaction> pendingTx = new ArrayList<>();
        for (byte[] b : pendingPoolTxBytes) {
            try {
                pendingTx.add(new AionTransaction(b));
            } catch (Throwable e) {
                LOGGER_TX.error("loadingCachePendingTx error {}", e.toString());
            }
        }

        Map<Address, SortedMap<BigInteger, AionTransaction>> sortedMap = new HashMap<>();
        for (AionTransaction tx : pendingTx) {
            if (sortedMap.get(tx.getFrom()) == null) {
                SortedMap<BigInteger, AionTransaction> accountSortedMap = new TreeMap<>();
                accountSortedMap.put(tx.getNonceBI(), tx);

                sortedMap.put(tx.getFrom(), accountSortedMap);
            } else {
                sortedMap.get(tx.getFrom()).put(tx.getNonceBI(), tx);
            }
        }

        List<AionTransaction> pendingPoolTx = new ArrayList<>();

        for (Map.Entry<Address, SortedMap<BigInteger, AionTransaction>> e : sortedMap.entrySet()) {
            pendingPoolTx.addAll(e.getValue().values());
        }

        addPendingTransactions(pendingPoolTx);
        long t2 = System.currentTimeMillis() - t1;
        LOGGER_TX
            .info("{} pendingPoolTx loaded from DB loaded into the txpool, {} ms",
                pendingPoolTx.size(), t2);
    }

    @Override
    public String getVersion() {
        return isSeed ? "0" : this.txPool.getVersion();
    }   // thread-safe?

    @Override
    public void updateBest() {
        getBestBlock();
    }   // thread-safe

}
