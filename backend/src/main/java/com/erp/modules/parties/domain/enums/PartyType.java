package com.erp.modules.parties.domain.enums;

/**
 * Whether a party is a natural person or an organisation (FR-PARTY-17).
 * Drives mandatory-identifier rules (BR-PARTY-04/05/07).
 */
public enum PartyType {
    INDIVIDUAL,
    BUSINESS
}
