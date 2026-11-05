package com.triples.rougether.adminapi.activity.error;

import com.triples.rougether.common.error.BusinessException;

public class AdminActivityDateInvalidException extends BusinessException {
    public AdminActivityDateInvalidException() {
        super(AdminActivityErrorCode.DATE_INVALID);
    }
}
