package com.jackal.group.tfx.gau.event;

import reactor.core.publisher.Flux;

import java.util.function.Consumer;

public class MessageSubscriber {
    public MessageSubscriber(Flux<Object> messages, Consumer<Object> consumer) { messages.subscribe(consumer); }
}