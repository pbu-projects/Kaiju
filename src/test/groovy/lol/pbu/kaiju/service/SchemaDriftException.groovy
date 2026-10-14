package lol.pbu.kaiju.service

import groovy.transform.CompileStatic

@CompileStatic
class SchemaDriftException extends ContractValidationException {
    SchemaDriftException(String message) {
        super(message)
    }

    SchemaDriftException(String message, Throwable cause) {
        super(message, cause)
    }
}
