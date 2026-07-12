package com.triples.rougether.infra.fcm;

import java.util.List;

public interface FcmSender {

    FcmSendResult send(List<String> tokens, String title, String body);
}
