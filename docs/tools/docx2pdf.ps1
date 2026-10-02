# Convert .docx files to PDF through Microsoft Word (COM), so the PDF keeps the Word
# layout, headings, bookmarks and embedded screenshots exactly as the .docx has them.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs/tools/docx2pdf.ps1 a.docx [b.docx ...]
#
# Writes <name>.pdf next to each input. Requires Word on the machine; build-manual.sh skips
# this step (with a message) where it is not available.
param([Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Docx)

$ErrorActionPreference = 'Stop'
$wdExportFormatPDF = 17
$wdExportOptimizeForPrint = 0
$wdExportCreateHeadingBookmarks = 1

$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
    foreach ($path in $Docx) {
        $full = (Resolve-Path $path).Path
        $pdf = [System.IO.Path]::ChangeExtension($full, '.pdf')
        $doc = $word.Documents.Open($full, $false, $true)   # ConfirmConversions=false, ReadOnly=true
        try {
            # Refresh the table of contents / fields so page numbers are right in the PDF.
            foreach ($toc in $doc.TablesOfContents) { $toc.Update() }
            $doc.Fields.Update() | Out-Null
            # ExportAsFixedFormat(OutputFileName, ExportFormat, OpenAfterExport, OptimizeFor,
            #   Range, From, To, Item, IncludeDocProps, KeepIRM, CreateBookmarks, DocStructureTags)
            $doc.ExportAsFixedFormat($pdf, $wdExportFormatPDF, $false, $wdExportOptimizeForPrint,
                0, 1, 1, 0, $true, $true, $wdExportCreateHeadingBookmarks, $true)
            Write-Output "PDF: $pdf"
        } finally {
            $doc.Close(0)   # wdDoNotSaveChanges
        }
    }
} finally {
    $word.Quit()
    [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($word)
}
