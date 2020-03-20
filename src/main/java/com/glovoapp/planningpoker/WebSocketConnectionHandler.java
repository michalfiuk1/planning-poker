package com.glovoapp.planningpoker;

import static com.glovoapp.planningpoker.ExceptionWithStatus.Status.SERVER_ERROR;
import static com.glovoapp.planningpoker.Message.Action.CLEAR_EVERYTHING;
import static com.glovoapp.planningpoker.Message.Action.GET_DATA;
import static com.glovoapp.planningpoker.Message.Action.NEW_PLAYER;
import static com.glovoapp.planningpoker.Message.Action.REMOVE_PLAYER;
import static com.glovoapp.planningpoker.Message.Action.SESSION_END;
import static com.glovoapp.planningpoker.Message.Action.SET_TICKET;
import static com.glovoapp.planningpoker.Message.Action.SHOW_VOTES;
import static com.glovoapp.planningpoker.Message.Action.STATE;
import static com.glovoapp.planningpoker.Message.Action.VOTE;
import static io.vertx.core.logging.LoggerFactory.getLogger;

import com.glovoapp.planningpoker.ApplicationState.Player;
import com.glovoapp.planningpoker.Message.Action;
import io.reactivex.Completable;
import io.vertx.core.Handler;
import io.vertx.core.logging.Logger;
import io.vertx.reactivex.core.http.ServerWebSocket;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class WebSocketConnectionHandler implements Handler<ServerWebSocket>, AutoCloseable {

    private final Logger log = getLogger(getClass());

    private final ApplicationStateHandler applicationStateHandler;

    @Override
    public final void handle(final ServerWebSocket socket) {
        final WebSocketWrapper wrapper = new WebSocketWrapper(socket);

        handleMessage(wrapper, new Message(GET_DATA, "lol never used"));

        socket.textMessageHandler(messageString -> {
            try {
                final Message message = Message.parse(messageString);
                handleMessage(wrapper, message);
            } catch (final Exception exception) {
                log.error("handling message failed", exception);
                if (!(exception instanceof ExceptionWithStatus)) {
                    socket.close(SERVER_ERROR.getCode(), "something went wrong when handling message");
                }
            }
        })
              .closeHandler(onClose -> handleMessage(wrapper, new Message(REMOVE_PLAYER, "lol never used")));
    }

    private void handleMessage(final WebSocketWrapper socket, final Message message) {
        final Action action = message.getAction();

        if (GET_DATA == action) {
            notifyStateChange(socket).subscribe();
        } else if (SET_TICKET == action) {
            final String ticketValue = message.getData();
            applicationStateHandler.setTicket(
                ticketValue,
                notifyStateChangeFunctionExcluding(socket)
            );
        } else if (NEW_PLAYER == action) {
            final String playerName = message.getData();
            applicationStateHandler.addPlayer(
                socket,
                new Player(playerName, ""),
                notifyStateChangeFunction()
            );
        } else if (VOTE == action) {
            final String vote = message.getData();
            if (vote.contains(":")) {
                throw new InvalidVoteException(vote);
            }
            applicationStateHandler.vote(
                socket,
                vote,
                notifyStateChangeFunction()
            );
        } else if (REMOVE_PLAYER == action) {
            applicationStateHandler.removePlayer(
                socket,
                notifyStateChangeFunction()
            );
        } else if (CLEAR_EVERYTHING == action) {
            applicationStateHandler.clearTicketNameAndVotes(notifyStateChangeFunction());
        } else if (SHOW_VOTES == action) {
            applicationStateHandler.showVotes(notifyStateChangeFunction());
        } else {
            throw new UnhandledActionException(action);
        }
    }

    private BiFunction<ApplicationState, WebSocketWrapper, Completable> notifyStateChangeFunctionExcluding(
        final WebSocketWrapper socket
    ) {
        return (newState, playerSocket) -> socket.equals(playerSocket)
            ? Completable.complete()
            : notifyStateChange(playerSocket, applicationStateHandler.serializeState(newState));
    }

    private BiFunction<ApplicationState, WebSocketWrapper, Completable> notifyStateChangeFunction() {
        return (newState, playerSocket) -> notifyStateChange(playerSocket,
            applicationStateHandler.serializeState(newState)
        );
    }

    private Completable notifyStateChange(final WebSocketWrapper socket) {
        return notifyStateChange(socket, applicationStateHandler.getCurrentSerializedState());
    }

    private Completable notifyStateChange(final WebSocketWrapper socket, final String state) {
        return socket.write(STATE + ":" + state);
    }

    @Override
    public void close() {
        applicationStateHandler.closeAllActiveConnections(socket -> socket.write(SESSION_END.name()));
    }

}
