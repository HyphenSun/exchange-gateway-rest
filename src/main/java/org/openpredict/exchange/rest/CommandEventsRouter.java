package org.openpredict.exchange.rest;

import lombok.extern.slf4j.Slf4j;
import org.openpredict.exchange.beans.L2MarketData;
import org.openpredict.exchange.beans.MatcherEventType;
import org.openpredict.exchange.beans.cmd.OrderCommand;
import org.openpredict.exchange.rest.events.*;
import org.openpredict.exchange.rest.events.admin.UserBalanceAdjustmentAdminEvent;
import org.openpredict.exchange.rest.events.admin.UserCreatedAdminEvent;
import org.openpredict.exchange.rest.model.internal.GatewayUserProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjLongConsumer;


@Service
@Slf4j
public class CommandEventsRouter implements ObjLongConsumer<OrderCommand> {

    @Autowired
    private GatewayState gatewayState;

//    @Autowired
//    private WebSocketServer webSocketServer;

    /**
     * TODO put non-latency-critical commands into a queue
     *
     * @param cmd command placeholder
     */
    @Override
    public void accept(OrderCommand cmd, long seq) {
        log.debug("seq={} EVENT CMD: {}", seq, cmd);

//        processData(seq, cmd);

//        final CommandResultCode resultCode = cmd.resultCode;
//        final int ticket = cmd.userCookie;
//
//
//        if (resp == null) {
//            log.error("can not find resp #{}", ticket);
//            return;
//        }
//
//        Object data = (resultCode == CommandResultCode.SUCCESS)
//                ? processData(cmd)
//                : null;
//
//        RestGenericResponse response = RestGenericResponse.builder()
//                .ticket(ticket)
//                .coreResultCode(resultCode.getCode())
//                .data(data)
//                .build();
//
//        resp.json(response).done();
//
//
        cmd.processMatcherEvents(evt -> {
            log.debug("INTERNAL EVENT: " + evt);

            if (evt.eventType == MatcherEventType.REJECTION) {
                GatewayUserProfile profile = gatewayState.getOrCreateUserProfile(evt.activeOrderUid);
                profile.rejectOrder(evt.activeOrderId);

            } else if (evt.eventType == MatcherEventType.CANCEL) {
                GatewayUserProfile profile = gatewayState.getOrCreateUserProfile(evt.activeOrderUid);
                profile.cancelOrder(evt.activeOrderId);
            }


        });

    }

    private void processData(long seq, OrderCommand cmd) {
        switch (cmd.command) {

            case PLACE_ORDER:
            case MOVE_ORDER:
            case CANCEL_ORDER:
                handleOrderCommand(cmd);
                break;

            case ORDER_BOOK_REQUEST:
                handleOrderBookCommand(cmd);
                break;

            case BALANCE_ADJUSTMENT:
                balanceAdjustment(cmd);
                break;

            case ADD_USER:
                handleAddUser(cmd);
                break;
        }
    }

    private OrderUpdateEvent handleOrderCommand(OrderCommand cmd) {
        List<OrderSizeChangeRecord> tradeRecords = new ArrayList<>();

        // TODO implement remaining size

        cmd.processMatcherEvents(evt -> {
            if (evt.eventType == MatcherEventType.TRADE) {

                MatchingRole role = evt.activeOrderId == cmd.orderId ? MatchingRole.TAKER : MatchingRole.MAKER;
                tradeRecords.add(NewTradeRecord.builder().filledSize(evt.size).fillPrice(evt.price).matchingRole(role).build());

            } else if (evt.eventType == MatcherEventType.CANCEL) {

                tradeRecords.add(ReduceRecord.builder().reducedSize(evt.size).build());

            } else if (evt.eventType == MatcherEventType.REJECTION) {

                tradeRecords.add(RejectionRecord.builder().rejectedSize(evt.size).build());

            } else {

                throw new UnsupportedOperationException("unknown event type");
            }
        });

        long activeSize = cmd.size - tradeRecords.stream().mapToLong(OrderSizeChangeRecord::getAffectedSize).sum();
        return OrderUpdateEvent.builder().price(cmd.price).orderId(cmd.orderId).activeSize(activeSize).trades(tradeRecords).build();
    }

    private UserBalanceAdjustmentAdminEvent balanceAdjustment(OrderCommand cmd) {
        UserBalanceAdjustmentAdminEvent apiEvent = UserBalanceAdjustmentAdminEvent.builder()
                .uid(cmd.uid)
                .transactionId(cmd.orderId)
                .amount(cmd.price)
                .balance(cmd.size)
                .build();
        //webSocketServer.broadcast(apiEvent);
        return apiEvent;
    }


    private OrderBookEvent handleOrderBookCommand(OrderCommand cmd) {

        if (cmd.marketData == null) {
            log.error("No market data object found");
            //future.response().code(500).done();
            //resp.chunk("{error:FAILED".getBytes());
            return null;
        }

        log.debug("MARKET DATA: " + cmd.marketData.dumpOrderBook());

        L2MarketData marketData = cmd.marketData;
        OrderBookEvent orderBook = new OrderBookEvent(
                "UNKNOWN",
                marketData.timestamp,
                marketData.getAskPricesCopy(),
                marketData.getAskVolumesCopy(),
                marketData.getBidPricesCopy(),
                marketData.getBidVolumesCopy()
        );

        //log.debug("req.isAsync()={} req.isDone()={}", req.isAsync(), req.isDone());

//        try {
//            Thread.sleep(10000);
//        } catch (InterruptedException e) {
//            //
//        }

        //resp.json(orderBook).done();

//        webSocketServer.broadcast(orderBook);

        return orderBook;
    }

    private UserCreatedAdminEvent handleAddUser(OrderCommand cmd) {
        UserCreatedAdminEvent apiEvent = UserCreatedAdminEvent.builder().uid(cmd.uid).build();
        //webSocketServer.broadcast(apiEvent);
        return apiEvent;
    }
}
