package lol.pbu.kaiju.service

class ContractValidationException extends RuntimeException {
    ContractValidationException(String message) {
        super(message)
    }

    ContractValidationException(String message, Throwable cause) {
        super(message, cause)
    }
}
