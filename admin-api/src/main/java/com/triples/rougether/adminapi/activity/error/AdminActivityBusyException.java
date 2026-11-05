package com.triples.rougether.adminapi.activity.error;

import com.triples.rougether.common.error.BusinessException;

public class AdminActivityBusyException extends BusinessException {
    public AdminActivityBusyException() { super(AdminActivityErrorCode.BUSY); }
}
