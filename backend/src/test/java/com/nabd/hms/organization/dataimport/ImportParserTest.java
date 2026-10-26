package com.nabd.hms.organization.dataimport;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADM-012 subtasks 2 and 3 without Spring: file reading, header auto-mapping (EN/HI/AR) and row validation. */
class ImportParserTest {

    @Test
    void xlsxReadsSharedStringsNumbersAndGaps() throws Exception {
        byte[] x = XlsxFixture.build(List.of(
                new String[]{"Name", "Mobile", "DOB", "Sex"},
                new String[]{"Asha <Rao> & Sons", "9876543210", "01/02/1990", "F"},
                new String[]{"Ravi", "", "03/04/1985", "M"}));
        List<String[]> rows = ImportFileReader.read(x, "xlsx");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).containsExactly("Asha <Rao> & Sons", "9876543210", "01/02/1990", "F");
        assertThat(rows.get(2)[1]).as("blank cell").isEmpty();
        assertThat(ImportFileReader.sniff(x, "xlsx")).isEqualTo("xlsx");
        assertThat(ImportFileReader.sniff(x, "csv")).as("a zip is not csv").isNull();
        assertThat(ImportFileReader.sniff("%PDF-1.4\0\0".getBytes(StandardCharsets.ISO_8859_1), "csv")).as("binary is not csv").isNull();
        assertThat(ImportFileReader.sniff("name,mobile\n".getBytes(StandardCharsets.UTF_8), "xlsx")).isNull();
    }

    @Test
    void xlsxRefusesDoctypeAndGarbage() {
        assertThatThrownBy(() -> ImportFileReader.read("PKnot a zip".getBytes(StandardCharsets.UTF_8), "xlsx")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void csvHandlesQuotesBomSemicolonAndBlankLines() {
        List<String[]> rows = ImportFileReader.csv("﻿Name;Mobile\r\n\"Doe, John\";\"98\"\"76\"\r\n\r\nB;2\r\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).containsExactly("Doe, John", "98\"76");
        assertThat(rows.get(0)[0]).isEqualTo("Name");
    }

    @Test
    void headersAreSuggestedInEnglishHindiAndArabic() {
        assertThat(ImportMapper.suggest(new String[]{"Patient Name", "Mobile No", "Date of Birth", "Gender", "UHID", "Allergies", "Notes"}))
                .containsEntry("name", "Patient Name").containsEntry("mobile", "Mobile No").containsEntry("dob", "Date of Birth")
                .containsEntry("sex", "Gender").containsEntry("oldRecordNo", "UHID").containsEntry("allergies", "Allergies").hasSize(6);
        assertThat(ImportMapper.suggest(new String[]{"नाम", "मोबाइल", "जन्म तिथि", "लिंग", "एलर्जी"}))
                .containsEntry("name", "नाम").containsEntry("mobile", "मोबाइल").containsEntry("dob", "जन्म तिथि").containsEntry("sex", "लिंग").containsEntry("allergies", "एलर्जी");
        assertThat(ImportMapper.suggest(new String[]{"الاسم", "رقم الجوال", "تاريخ الميلاد", "الجنس", "حساسية"}))
                .containsEntry("name", "الاسم").containsEntry("mobile", "رقم الجوال").containsEntry("dob", "تاريخ الميلاد").containsEntry("sex", "الجنس").containsEntry("allergies", "حساسية");
        assertThat(ImportMapper.suggest(new String[]{"Col A", "Col B"})).isEmpty();
    }

    private static final Map<String, Integer> IDX = Map.of("name", 0, "mobile", 1, "dob", 2, "sex", 3, "oldRecordNo", 4, "allergies", 5);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private static ImportMapper.Result row(String... c) {
        return ImportMapper.normalize(c, IDX, "dd/MM/yyyy", "IN", TODAY);
    }

    @Test
    void rowValidationGivesCodesNotValues() {
        ImportMapper.Result ok = row("Asha Rao", "98765 43210", "01/02/1990", "Female", "OLD-7", "Penicillin; Peanuts, none");
        assertThat(ok.rec().phone()).isEqualTo("+919876543210");
        assertThat(ok.rec().dob()).isEqualTo(LocalDate.of(1990, 2, 1));
        assertThat(ok.rec().sex()).isEqualTo("female");
        assertThat(ok.rec().allergies()).containsExactly("Penicillin", "Peanuts");
        assertThat(row("", "9876543210", "01/02/1990", "F", "", "").errorCode()).isEqualTo("NAME_MISSING");
        assertThat(row("A", "", "01/02/1990", "F", "", "").errorCode()).isEqualTo("MOBILE_MISSING");
        assertThat(row("A", "12345", "01/02/1990", "F", "", "").errorCode()).isEqualTo("MOBILE_INVALID");
        assertThat(row("A", "9876543210", "", "F", "", "").errorCode()).isEqualTo("DOB_MISSING");
        assertThat(row("A", "9876543210", "31/02/1990", "F", "", "").errorCode()).as("strict calendar").isEqualTo("DOB_INVALID");
        assertThat(row("A", "9876543210", "02/01/2030", "F", "", "").errorCode()).as("future").isEqualTo("DOB_INVALID");
        assertThat(row("A", "9876543210", "01/02/1990", "?", "", "").errorCode()).isEqualTo("SEX_INVALID");
        assertThat(row("A", "9876543210", "32874", "M", "", "").rec().dob()).as("Excel serial 32874 = 1990-01-01").isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(row("A", "9876543210", "1990-01-02", "पुरुष", "", "").rec().dob()).as("ISO always accepted").isEqualTo(LocalDate.of(1990, 1, 2));
        assertThat(ImportMapper.normalize(new String[]{"A", "9876543210", "03/04/1990", "m"}, IDX, "MM/dd/yyyy", "IN", TODAY).rec().dob()).isEqualTo(LocalDate.of(1990, 3, 4));
    }
}
