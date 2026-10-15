package lol.pbu.kaiju.service

import groovy.transform.CompileStatic

@CompileStatic
class ContractValidationException extends RuntimeException {
    ContractValidationException(String message) {
        super(message)
    }

    ContractValidationException(String message, Throwable cause) {
        super(message, cause)
    }
}
