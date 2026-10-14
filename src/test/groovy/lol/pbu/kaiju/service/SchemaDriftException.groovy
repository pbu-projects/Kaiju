package lol.pbu.kaiju.service

class SchemaDriftException extends ContractValidationException {
    SchemaDriftException(String message) {
        super(message)
    }

    SchemaDriftException(String message, Throwable cause) {
        super(message, cause)
    }
}
