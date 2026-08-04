package com.triples.rougether.userapi.global.alert;

import com.triples.rougether.common.error.ErrorCode;

public interface OperationalAlertNotifier {

    void notifyBusiness(String endpoint, ErrorCode errorCode);

    void notifyUnexpected(String endpoint, Throwable cause);
}
