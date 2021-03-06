//
// Swiss QR Bill Generator
// Copyright (c) 2017 Manuel Bleichenbacher
// Licensed under MIT License
// https://opensource.org/licenses/MIT
//
package net.codecrete.qrbill.generator;

import java.util.Locale;

import net.codecrete.qrbill.generator.PaymentValidation.CleaningResult;
import net.codecrete.qrbill.generator.ValidationMessage.Type;


/**
 * Validates and cleans QR bill data.
 */
class Validator {

    private Bill billIn;
    private Bill billOut;
    private ValidationResult validationResult;


    /**
     * Validates the QR bill data and returns the validation messages (if any) and the cleaned bill data.
     * 
     * @param bill bill data to validate
     * @return validation result
     */
    static ValidationResult validate(Bill bill) {
        Validator validator = new Validator(bill);
        return validator.validateBill();
    }

    private Validator(Bill bill) {
        billIn = bill;
        billOut = new Bill();
        validationResult = new ValidationResult();
    }

    private ValidationResult validateBill() {

        billOut.setLanguage(billIn.getLanguage());
        billOut.setVersion(billIn.getVersion());

        validateCurrency();
        validateAmount();
        boolean isQRBillIBAN = validateAccountNumber();
        validateCreditor();
        validateFinalCreditor();
        validateReferenceNo(isQRBillIBAN);
        validateAdditionalInformation();
        validateDebtor();
        validateDueDate();

        validationResult.setCleanedBill(billOut);
        return validationResult;
    }

    private void validateCurrency() {
        String currency = Strings.trimmed(billIn.getCurrency());
        if (validateMandatory(currency, Bill.FIELD_CURRENCY)) {
            currency = currency.toUpperCase(Locale.US);
            if (!"CHF".equals(currency) && !"EUR".equals(currency)) {
                validationResult.addMessage(Type.ERROR, Bill.FIELD_CURRENCY, QRBill.KEY_CURRENCY_IS_CHF_OR_EUR);
            } else {
                billOut.setCurrency(currency);
            }
        }
    }

    private void validateAmount() {
        Double amount = billIn.getAmount();
        if (amount == null) {
            billOut.setAmount(null);
        } else {
            amount = Math.round(amount * 100) / 100.0; // round to multiple of 0.01
            if (amount < 0.01 || amount > 999999999.99) {
                validationResult.addMessage(Type.ERROR, Bill.FIELD_AMOUNT, QRBill.KEY_AMOUNT_IS_IN_VALID_RANGE);
            } else {
                billOut.setAmount(amount);
            }
        }
    }

    private boolean validateAccountNumber() {
        boolean isQRBillIBAN = false;
        String account = Strings.trimmed(billIn.getAccount());
        if (validateMandatory(account, Bill.FIELD_ACCOUNT)) {
            account = Strings.whiteSpaceRemoved(account).toUpperCase(Locale.US);
            if (validateIBAN(account, Bill.FIELD_ACCOUNT)) {
                if (!account.startsWith("CH") && !account.startsWith("LI")) {
                    validationResult.addMessage(Type.ERROR, Bill.FIELD_ACCOUNT, QRBill.KEY_ACCOUNT_IS_CH_LI_IBAN);
                } else if (account.length() != 21) {
                    validationResult.addMessage(Type.ERROR, Bill.FIELD_ACCOUNT, QRBill.KEY_ACCOUNT_IS_VALID_IBAN);
                } else {
                    billOut.setAccount(account);
                    isQRBillIBAN = account.charAt(4) == '3' && (account.charAt(5) == '0' || account.charAt(5) == '1');
                }
            }
        }
        return isQRBillIBAN;
    }

    private void validateCreditor() {
        Address creditor = validatePerson(billIn.getCreditor(), Bill.FIELDROOT_CREDITOR, true);
        billOut.setCreditor(creditor);
    }

    private void validateFinalCreditor() {
        Address finalCreditor = validatePerson(billIn.getFinalCreditor(), Bill.FIELDROOT_FINAL_CREDITOR, false);
        billOut.setFinalCreditor(finalCreditor);
    }

    private void validateReferenceNo(boolean isQRBillIBAN) {
        String referenceNo = Strings.trimmed(billIn.getReferenceNo());

        if (referenceNo == null) {
            if (isQRBillIBAN)
                validationResult.addMessage(Type.ERROR, Bill.FIELD_REFERENCE_NO, QRBill.KEY_MANDATORY_FOR_QR_IBAN);
            return;
        }

        referenceNo = Strings.whiteSpaceRemoved(referenceNo);
        if (referenceNo.startsWith("RF")) {
            if (!PaymentValidation.isValidISO11649ReferenceNo(referenceNo)) {
                validationResult.addMessage(Type.ERROR, Bill.FIELD_REFERENCE_NO, QRBill.KEY_VALID_ISO11649_CREDITOR_REF);
            } else {
                billOut.setReferenceNo(referenceNo);
            }
        } else {
            if (referenceNo.length() < 27)
                referenceNo = "00000000000000000000000000".substring(0, 27 - referenceNo.length()) + referenceNo;
            if (!PaymentValidation.isValidQRReferenceNo(referenceNo))
                validationResult.addMessage(Type.ERROR, Bill.FIELD_REFERENCE_NO, QRBill.KEY_VALID_QR_REF_NO);
            else
                billOut.setReferenceNo(referenceNo);
        }
    }

    private void validateAdditionalInformation() {
        String additionalInfo = Strings.trimmed(billIn.getAdditionalInfo());
        additionalInfo = clippedValue(additionalInfo, 140, Bill.FIELD_ADDITIONAL_INFO);
        billOut.setAdditionalInfo(additionalInfo);
    }

    private void validateDebtor() {
        Address debtor = validatePerson(billIn.getDebtor(), Bill.FIELDROOT_DEBTOR, false);
        billOut.setDebtor(debtor);
    }

    private void validateDueDate() {
        billOut.setDueDate(billIn.getDueDate());
    }

    private Address validatePerson(Address addressIn, String fieldRoot, boolean mandatory) {
        Address addressOut = cleanedPerson(addressIn, fieldRoot);
        if (addressOut == null) {
            if (mandatory) {
                validationResult.addMessage(Type.ERROR, fieldRoot + Bill.SUBFIELD_NAME, QRBill.KEY_FIELD_IS_MANDATORY);
                validationResult.addMessage(Type.ERROR, fieldRoot + Bill.SUBFIELD_POSTAL_CODE, QRBill.KEY_FIELD_IS_MANDATORY);
                validationResult.addMessage(Type.ERROR, fieldRoot + Bill.SUBFIELD_TOWN, QRBill.KEY_FIELD_IS_MANDATORY);
                validationResult.addMessage(Type.ERROR, fieldRoot + Bill.SUBFIELD_COUNTRY_CODE, QRBill.KEY_FIELD_IS_MANDATORY);
            }
            return null;
        }

        if (addressOut.getCountryCode() != null)
            addressOut.setCountryCode(addressOut.getCountryCode().toUpperCase(Locale.US));

        validateMandatory(addressOut.getName(), fieldRoot, Bill.SUBFIELD_NAME);
        validateMandatory(addressOut.getPostalCode(), fieldRoot, Bill.SUBFIELD_POSTAL_CODE);
        validateMandatory(addressOut.getTown(), fieldRoot, Bill.SUBFIELD_TOWN);
        validateMandatory(addressOut.getCountryCode(), fieldRoot, Bill.SUBFIELD_COUNTRY_CODE);

        addressOut.setName(clippedValue(addressOut.getName(), 70, fieldRoot, Bill.SUBFIELD_NAME));
        addressOut.setStreet(clippedValue(addressOut.getStreet(), 70, fieldRoot, Bill.SUBFIELD_STREET));
        addressOut.setHouseNo(clippedValue(addressOut.getHouseNo(), 16, fieldRoot, Bill.SUBFIELD_HOUSE_NO));
        addressOut.setPostalCode(clippedValue(addressOut.getPostalCode(), 16, fieldRoot, Bill.SUBFIELD_POSTAL_CODE));
        addressOut.setTown(clippedValue(addressOut.getTown(), 35, fieldRoot, Bill.SUBFIELD_TOWN));

        if (addressOut.getCountryCode() != null
            && (addressOut.getCountryCode().length() != 2
                    || !PaymentValidation.isAlphaNumeric(addressOut.getCountryCode())))
                validationResult.addMessage(Type.ERROR, fieldRoot + Bill.SUBFIELD_COUNTRY_CODE, QRBill.KEY_VALID_COUNTRY_CODE);

        return addressOut;
    }

    private boolean validateIBAN(String iban, String field) {
        if (!PaymentValidation.isValidIBAN(iban)) {
            validationResult.addMessage(Type.ERROR, field, QRBill.KEY_ACCOUNT_IS_VALID_IBAN);
            return false;
        }
        return true;
    }

    private Address cleanedPerson(Address addressIn, String fieldRoot) {
        if (addressIn == null)
            return null;
        Address addressOut = new Address();
        addressOut.setName(cleanedValue(addressIn.getName(), fieldRoot, Bill.SUBFIELD_NAME));
        addressOut.setStreet(cleanedValue(addressIn.getStreet(), fieldRoot, Bill.SUBFIELD_STREET));
        addressOut.setHouseNo(cleanedValue(addressIn.getHouseNo(), fieldRoot, Bill.SUBFIELD_HOUSE_NO));
        addressOut.setPostalCode(cleanedValue(addressIn.getPostalCode(), fieldRoot, Bill.SUBFIELD_POSTAL_CODE));
        addressOut.setTown(cleanedValue(addressIn.getTown(), fieldRoot, Bill.SUBFIELD_TOWN));
        addressOut.setCountryCode(Strings.trimmed(addressIn.getCountryCode()));

        if (addressOut.getName() == null && addressOut.getStreet() == null
                && addressOut.getHouseNo() == null && addressOut.getPostalCode() == null
                && addressOut.getTown() == null && addressOut.getCountryCode() == null)
            return null;

        return addressOut;
    }

    private boolean validateMandatory(String value, String field) {
        if (Strings.isNullOrEmpty(value)) {
            validationResult.addMessage(Type.ERROR, field, QRBill.KEY_FIELD_IS_MANDATORY);
            return false;
        }

        return true;
    }

    private boolean validateMandatory(String value, String fieldRoot, String subfield) {
        if (Strings.isNullOrEmpty(value)) {
            validationResult.addMessage(Type.ERROR, fieldRoot + subfield, QRBill.KEY_FIELD_IS_MANDATORY);
            return false;
        }

        return true;
    }

    private String clippedValue(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            validationResult.addMessage(Type.WARNING, field, QRBill.KEY_FIELD_CLIPPED, new String[] { Integer.toString(maxLength) });
            return value.substring(0, maxLength);
        }

        return value;
    }

    private String clippedValue(String value, int maxLength, String fieldRoot, String subfield) {
        if (value != null && value.length() > maxLength) {
            validationResult.addMessage(Type.WARNING, fieldRoot + subfield, QRBill.KEY_FIELD_CLIPPED, new String[] { Integer.toString(maxLength) });
            return value.substring(0, maxLength);
        }

        return value;
    }


    private String cleanedValue(String value, String fieldRoot, String subfield) {
        CleaningResult result = new CleaningResult();
        PaymentValidation.cleanValue(value, result);
        if (result.replacedUnsupportedChars)
            validationResult.addMessage(Type.WARNING, fieldRoot + subfield, QRBill.KEY_REPLACED_UNSUPPORTED_CHARACTERS);
        return result.cleanedString;
    }
}
