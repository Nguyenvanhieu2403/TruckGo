package com.truckgo.driver;

import com.truckgo.common.exception.FieldErrorException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalises licence data (upper case, no spaces/dashes) and checks it against the configured format and classes. */
@Component
public class DriverLicenseValidator {
    public record License(String number, String licenseClass) { }

    private final Pattern numberPattern;
    private final List<String> allowedClasses;

    public DriverLicenseValidator(DriverProperties properties) {
        this.numberPattern = Pattern.compile(properties.licenseNumberPattern());
        this.allowedClasses = properties.allowedLicenseClasses().stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).toList();
    }

    public License normalize(String number, String licenseClass) {
        String n = number == null ? "" : number.replaceAll("[\\s.\\-]", "").toUpperCase(Locale.ROOT);
        if (!numberPattern.matcher(n).matches()) throw FieldErrorException.invalid("licenseNumber", "Số giấy phép lái xe không đúng định dạng");
        String c = licenseClass == null ? "" : licenseClass.trim().toUpperCase(Locale.ROOT);
        if (!allowedClasses.contains(c)) throw FieldErrorException.invalid("licenseClass", "Hạng giấy phép không được hỗ trợ cho xe tải (" + String.join(", ", allowedClasses) + ")");
        return new License(n, c);
    }

    public List<String> allowedClasses() { return allowedClasses; }
}
