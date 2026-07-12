package com.triples.rougether.infra.fcm;

import java.util.List;

public interface FcmSender {

    List<String> send(List<String> tokens, String title, String body);
}
