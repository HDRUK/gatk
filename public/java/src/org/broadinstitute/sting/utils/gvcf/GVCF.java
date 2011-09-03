/*
 * Copyright (c) 2011, The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.gvcf;

import org.broadinstitute.sting.utils.QualityUtils;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.codecs.vcf.StandardVCFWriter;
import org.broadinstitute.sting.utils.codecs.vcf.VCFConstants;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.variantcontext.Allele;
import org.broadinstitute.sting.utils.variantcontext.Genotype;
import org.broadinstitute.sting.utils.variantcontext.VariantContext;

import java.io.*;
import java.util.*;

/**
 * GATK binary VCF record
 *
 * @author Your Name
 * @since Date created
 */
public class GVCF {
    private final static int RECORD_TERMINATOR = 123456789;
    private int chromOffset;
    private int start, stop;
    private String id;
    private List<Allele> alleleMap;
    private int alleleOffsets[];
    private float qual;
    private byte refPad;
    private String info;
    private int filterOffset;

    private List<GVCFGenotype> genotypes = Collections.emptyList();

    public GVCF(final GVCFHeaderBuilder gvcfHeaderBuilder, final VariantContext vc, boolean skipGenotypes) {
        chromOffset = gvcfHeaderBuilder.encodeString(vc.getChr());
        start = vc.getStart();
        stop = vc.getEnd();
        refPad = vc.hasReferenceBaseForIndel() ? vc.getReferenceBaseForIndel() : 0;
        id = vc.getID();

        // encode alleles
        alleleMap = new ArrayList<Allele>(vc.getNAlleles());
        alleleOffsets = new int[vc.getNAlleles()];
        alleleMap.add(vc.getReference());
        alleleOffsets[0] = gvcfHeaderBuilder.encodeAllele(vc.getReference());
        for ( int i = 0; i < vc.getAlternateAlleles().size(); i++ ) {
            alleleMap.add(vc.getAlternateAllele(i));
            alleleOffsets[i+1] = gvcfHeaderBuilder.encodeAllele(vc.getAlternateAllele(i));
        }

        qual = (float)vc.getNegLog10PError(); //qualToByte(vc.getPhredScaledQual());
        info = infoFieldString(vc, gvcfHeaderBuilder);
        filterOffset = gvcfHeaderBuilder.encodeString(StandardVCFWriter.getFilterString(vc));

        if ( ! skipGenotypes ) {
            genotypes = encodeGenotypes(gvcfHeaderBuilder, vc);
        }
    }

    public GVCF(DataInputStream inputStream, boolean skipGenotypes) throws IOException {
        chromOffset = inputStream.readInt();
        start = inputStream.readInt();
        stop = inputStream.readInt();
        id = inputStream.readUTF();
        refPad = inputStream.readByte();
        alleleOffsets = readIntArray(inputStream);
        qual = inputStream.readFloat();
        info = inputStream.readUTF();
        filterOffset = inputStream.readInt();

        int nGenotypes = inputStream.readInt();
        int sizeOfGenotypes = inputStream.readInt();
        if ( skipGenotypes ) {
            genotypes = Collections.emptyList();
            inputStream.skipBytes(sizeOfGenotypes);
        } else {
            genotypes = new ArrayList<GVCFGenotype>(nGenotypes);
            for ( int i = 0; i < nGenotypes; i++ )
                genotypes.add(new GVCFGenotype(this, inputStream));
        }

        int recordDone = inputStream.readInt();
        if ( recordDone != RECORD_TERMINATOR )
            throw new UserException.MalformedFile("Record not terminated by RECORD_TERMINATOR key");
    }

    public VariantContext decode(final String source, final GVCFHeader header) {
        final String contig = header.getString(chromOffset);
        alleleMap = header.getAlleles(alleleOffsets);
        double negLog10PError = qual; // QualityUtils.qualToErrorProb(qual);
        Set<String> filters = header.getFilters(filterOffset);
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put("INFO", info);
        Byte refPadByte = refPad == 0 ? null : refPad;
        Map<String, Genotype> genotypes = decodeGenotypes(header);

        return new VariantContext(source, contig, start, stop, alleleMap, genotypes, negLog10PError, filters, attributes, refPadByte);
    }

    private Map<String, Genotype> decodeGenotypes(final GVCFHeader header) {
        if ( genotypes.isEmpty() )
            return VariantContext.NO_GENOTYPES;
        else {
            Map<String, Genotype> map = new TreeMap<String, Genotype>();

            for ( int i = 0; i < genotypes.size(); i++ ) {
                final String sampleName = header.getSample(i);
                final Genotype g = genotypes.get(i).decode(sampleName, header, this, alleleMap);
                map.put(sampleName, g);
            }

            return map;
        }
    }

    private List<GVCFGenotype> encodeGenotypes(final GVCFHeaderBuilder gvcfHeaderBuilder, final VariantContext vc) {
        int nGenotypes = vc.getNSamples();
        if ( nGenotypes > 0 ) {
            List<GVCFGenotype> genotypes = new ArrayList<GVCFGenotype>(nGenotypes);
            for ( int i = 0; i < nGenotypes; i++ ) genotypes.add(null);

            for ( Genotype g : vc.getGenotypes().values() ) {
                int i = gvcfHeaderBuilder.encodeSample(g.getSampleName());
                genotypes.set(i, new GVCFGenotype(gvcfHeaderBuilder, alleleMap, g));
            }

            return genotypes;
        } else {
            return Collections.emptyList();
        }
    }

    public int getNAlleles() { return alleleOffsets.length; }

    public int write(DataOutputStream outputStream) throws IOException {
        int startSize = outputStream.size();
        outputStream.writeInt(chromOffset);
        outputStream.writeInt(start);
        outputStream.writeInt(stop);
        outputStream.writeUTF(id);
        outputStream.writeByte(refPad);
        writeIntArray(alleleOffsets, outputStream, true);
        outputStream.writeFloat(qual);
        outputStream.writeUTF(info);
        outputStream.writeInt(filterOffset);

        int nGenotypes = genotypes.size();
        int expectedSizeOfGenotypes = nGenotypes == 0 ? 0 : genotypes.get(0).sizeInBytes() * nGenotypes;
        outputStream.writeInt(nGenotypes);
        outputStream.writeInt(expectedSizeOfGenotypes);
        int obsSizeOfGenotypes = 0;
        for ( GVCFGenotype g : genotypes )
            obsSizeOfGenotypes += g.write(outputStream);
        if ( obsSizeOfGenotypes != expectedSizeOfGenotypes )
            throw new RuntimeException("Expect and observed genotype sizes disagree! expect = " + expectedSizeOfGenotypes + " obs =" + obsSizeOfGenotypes);

        outputStream.writeInt(RECORD_TERMINATOR);
        return outputStream.size() - startSize;
    }

    private final String infoFieldString(VariantContext vc, final GVCFHeaderBuilder gvcfHeaderBuilder) {
        StringBuilder s = new StringBuilder();

        boolean first = true;
        for ( Map.Entry<String, Object> field : vc.getAttributes().entrySet() ) {
            String key = field.getKey();
            if ( key.equals(VariantContext.ID_KEY) || key.equals(VariantContext.UNPARSED_GENOTYPE_MAP_KEY) || key.equals(VariantContext.UNPARSED_GENOTYPE_PARSER_KEY) )
                continue;
            int stringIndex = gvcfHeaderBuilder.encodeString(key);
            String outputValue = StandardVCFWriter.formatVCFField(field.getValue());
            if ( outputValue != null ) {
                if ( ! first ) s.append(";");
                s.append(stringIndex).append("=").append(outputValue);
                first = false;
            }
        }

        return s.toString();
    }

    private final static int BUFFER_SIZE = 1048576; // 2**20
    public static DataOutputStream createOutputStream(final File file) throws FileNotFoundException {
        return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), BUFFER_SIZE));
    }

    public static DataInputStream createInputStream(final File file) throws FileNotFoundException {
        return new DataInputStream(new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE));
    }

    protected final static int[] readIntArray(final DataInputStream inputStream) throws IOException {
        return readIntArray(inputStream, inputStream.readInt());
    }

    protected final static int[] readIntArray(final DataInputStream inputStream, int size) throws IOException {
        int[] array = new int[size];
        for ( int i = 0; i < array.length; i++ )
            array[i] = inputStream.readInt();
        return array;
    }

    protected final static void writeIntArray(int[] array, final DataOutputStream outputStream, boolean writeSize) throws IOException {
        if ( writeSize ) outputStream.writeInt(array.length);
        for ( int i : array )
            outputStream.writeInt(i);
    }

    protected final static byte[] readByteArray(final DataInputStream inputStream) throws IOException {
        return readByteArray(inputStream, inputStream.readInt());
    }

    protected final static byte[] readByteArray(final DataInputStream inputStream, int size) throws IOException {
        byte[] array = new byte[size];
        for ( int i = 0; i < array.length; i++ )
            array[i] = inputStream.readByte();
        return array;
    }

    protected final static void writeByteArray(byte[] array, final DataOutputStream outputStream, boolean writeSize) throws IOException {
        if ( writeSize ) outputStream.writeInt(array.length);
        for ( byte i : array )
            outputStream.writeByte(i);
    }

    protected final static byte qualToByte(double phredScaledQual) {
        return (byte)Math.round(Math.min(phredScaledQual, 255));
    }
}
