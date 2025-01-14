package org.apache.phoenix.jdbc;


import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.phoenix.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * An HAURLInfo contains information of an HA Url with respect of HA Group Name.
 * <p>
 * It is constructed based on client input, including the JDBC connection string and properties.
 * Objects of this class are used to get appropriate principal and additional JDBC parameters.
 * <p>
 * This class is immutable.
 */

@VisibleForTesting
public class HAURLInfo {
    private final String name;
    private final String principal;
    private final String additionalJDBCParams;

    HAURLInfo(String name, String principal, String additionalJDBCParams) {
        Preconditions.checkNotNull(name);
        this.name = name;
        this.principal = principal;
        this.additionalJDBCParams = additionalJDBCParams;
    }

    HAURLInfo(String name, String principal) {
        this(name, principal, null);
    }

    HAURLInfo(String name) {
        this(name, null, null);
    }

    public String getName() {
        return name;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getAdditionalJDBCParams() {
        return additionalJDBCParams;
    }

    @Override
    public String toString() {
        if (principal != null) {
            return String.format("%s[%s]", name, principal);
        }
        return name;
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (other == this) {
            return true;
        }
        if (other.getClass() != getClass()) {
            return false;
        }
        HAURLInfo otherInfo = (HAURLInfo) other;
        return new EqualsBuilder()
                .append(name, otherInfo.name)
                .append(principal, otherInfo.principal)
                .isEquals();
    }

    @Override
    public int hashCode() {
        if (principal != null) {
            return new HashCodeBuilder(7, 47)
                    .append(name)
                    .append(principal).hashCode();
        }
        return new HashCodeBuilder(7, 47).append(name).hashCode();
    }

}