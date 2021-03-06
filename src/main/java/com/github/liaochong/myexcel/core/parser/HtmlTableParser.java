/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.liaochong.myexcel.core.parser;

import com.github.liaochong.myexcel.core.constant.Constants;
import com.github.liaochong.myexcel.core.style.FontStyle;
import com.github.liaochong.myexcel.utils.RegexpUtil;
import com.github.liaochong.myexcel.utils.StringUtil;
import com.github.liaochong.myexcel.utils.StyleUtil;
import com.github.liaochong.myexcel.utils.TdUtil;
import org.apache.commons.codec.CharEncoding;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * html table parser
 *
 * @author liaochong
 * @version 1.0
 */
public class HtmlTableParser {

    private static final Pattern DOUBLE_PATTERN = Pattern.compile("^[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE]([-+]?([012]?\\d{1,2}|30[0-7])|-3([01]?[4-9]|[012]?[0-3])))?[dD]?$");

    private static final Pattern LINE_FEED_PATTERN = Pattern.compile("\\\\n");
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(HtmlTableParser.class);

    private ParseConfig parseConfig;

    private File htmlFile;

    private String html;

    private Map<String, String> defaultLinkStyle = new HashMap<>();

    private XSSFRichTextString spanText;

    private HtmlTableParser() {
        defaultLinkStyle.put(FontStyle.FONT_COLOR, "blue");
        defaultLinkStyle.put(FontStyle.TEXT_DECORATION, FontStyle.UNDERLINE);
    }

    public static HtmlTableParser of(File htmlFile) {
        Objects.requireNonNull(htmlFile);
        HtmlTableParser htmlTableParser = new HtmlTableParser();
        htmlTableParser.htmlFile = htmlFile;
        return htmlTableParser;
    }

    public static HtmlTableParser of(String html) {
        Objects.requireNonNull(html);
        HtmlTableParser htmlTableParser = new HtmlTableParser();
        htmlTableParser.html = html;
        return htmlTableParser;
    }

    /**
     * 获取所有表格
     *
     * @param parseConfig 解析配置
     * @return 所有表格
     * @throws IOException IOException
     */
    public List<Table> getAllTable(ParseConfig parseConfig) throws IOException {
        log.info("Start parsing html file");
        long startTime = System.currentTimeMillis();
        Document document;
        if (htmlFile != null) {
            document = Jsoup.parse(htmlFile, CharEncoding.UTF_8);
        } else {
            document = Jsoup.parse(html, CharEncoding.UTF_8);
        }
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        //select all <br> tags and append \n after that
        document.select("br").after("\\n");
        //select all <p> tags and prepend \n before that
        document.select("p").before("\\n");
        this.parseConfig = parseConfig;
        Elements tableElements = document.getElementsByTag(HtmlTag.table.name());
        List<Table> result = tableElements.stream().map(tableElement -> {
            Table table = new Table();
            Elements captionElements = tableElement.getElementsByTag(HtmlTag.caption.name());
            if (!captionElements.isEmpty()) {
                table.setCaption(captionElements.first().text());
            }
            this.parseTrOfTable(table, tableElement, StyleUtil.parseStyle(tableElement));
            return table;
        }).collect(Collectors.toList());
        log.info("Complete html file parsing,takes {} ms", System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * 解析table中的tr
     *
     * @param table table
     */
    private void parseTrOfTable(Table table, Element tableElement, Map<String, String> tableStyle) {
        Map<Element, Map<String, String>> parentStyleMap = new ConcurrentHashMap<>();

        Elements trElements = tableElement.getElementsByTag(HtmlTag.tr.name());
        final Map<Integer, List<Integer>> seizeMap = new HashMap<>();
        List<Tr> trList = IntStream.range(0, trElements.size()).mapToObj(index -> {
            Element trElement = trElements.get(index);
            Element parent = trElement.parent();
            Map<String, String> upperStyle;
            if (Objects.equals(parent, tableElement)) {
                upperStyle = tableStyle;
            } else {
                if (parentStyleMap.containsKey(parent)) {
                    upperStyle = parentStyleMap.get(parent);
                } else {
                    upperStyle = StyleUtil.mixStyle(tableStyle, StyleUtil.parseStyle(parent));
                    parentStyleMap.putIfAbsent(parent, upperStyle);
                }
            }
            Map<String, String> trStyleMap = StyleUtil.mixStyle(upperStyle, StyleUtil.parseStyle(trElement));
            String height = trStyleMap.get("height");
            Tr tr = new Tr(index, TdUtil.getValue(height), true);
            // 行可见性
            tr.setVisibility(!Objects.equals(trStyleMap.get("visibility"), "hidden"));
            this.parseTdOfTr(tr, trElement, trStyleMap, seizeMap);
            return tr;
        }).collect(Collectors.toCollection(LinkedList::new));
        table.setTrList(trList);
    }

    /**
     * 获取tr中的td
     *
     * @param tr        tr
     * @param trElement trElement
     * @param trStyle   trStyle
     * @param seizeMap  seizeMap 占位map
     */
    private void parseTdOfTr(Tr tr, Element trElement, Map<String, String> trStyle, Map<Integer, List<Integer>> seizeMap) {
        Elements tdElements = trElement.children();
        if (tdElements.isEmpty()) {
            tr.setTdList(Collections.emptyList());
            tr.setColWidthMap(Collections.emptyMap());
            return;
        }

        final List<Td> tdList = new LinkedList<>();
        final Map<Integer, Integer> colWidthMap = new HashMap<>(tdElements.size());
        List<Integer> seizeOfTr = seizeMap.getOrDefault(tr.getIndex(), Collections.emptyList());
        // 单元格偏移量
        int shift = 0;
        for (int i = 0, size = tdElements.size(); i < size; i++) {
            Element tdElement = tdElements.get(i);
            Td td = new Td(tr.getIndex(), i + shift);
            this.setTdContent(tdElement, td);

            td.setTh(Objects.equals(HtmlTag.th.name(), tdElement.tagName()));
            Map<String, String> tdStyle = StyleUtil.parseStyle(tdElement);
            if (tdStyle.isEmpty() && ContentTypeEnum.isLink(td.getTdContentType())) {
                tdStyle = defaultLinkStyle;
            }
            td.setStyle(StyleUtil.mixStyle(trStyle, tdStyle));

            String colSpan = tdElement.attr(HtmlTag.colspan.name());
            td.setColSpan(TdUtil.getSpan(colSpan));

            String rowSpan = tdElement.attr(HtmlTag.rowspan.name());
            td.setRowSpan(TdUtil.getSpan(rowSpan));

            if (!seizeOfTr.isEmpty()) {
                List<Integer> checkedPositions = new ArrayList<>();
                while (true) {
                    List<Integer> seizePositions = seizeOfTr.stream().filter(s -> td.getCol() >= s).collect(Collectors.toList());
                    if (!checkedPositions.isEmpty()) {
                        seizePositions.removeAll(checkedPositions);
                    }
                    if (seizePositions.isEmpty()) {
                        break;
                    }
                    td.setCol(td.getCol() + seizePositions.size());
                    checkedPositions.addAll(seizePositions);
                }
            }

            if (td.getRowSpan() > 1) {
                for (int j = 1, length = td.getRowSpan(); j < length; j++) {
                    int rowNum = tr.getIndex() + j;
                    List<Integer> seizePosOfTr = seizeMap.get(rowNum);
                    if (Objects.isNull(seizePosOfTr)) {
                        seizePosOfTr = new ArrayList<>();
                        seizeMap.put(rowNum, seizePosOfTr);
                    }
                    IntStream.rangeClosed(td.getCol(), td.getColBound()).forEach(seizePosOfTr::add);
                }
            }

            if (td.getColSpan() > 0) {
                shift += td.getColSpan() - 1;
            }
            tdList.add(td);

            // 设置每列宽度
            if (parseConfig.isComputeAutoWidth()) {
                int width = TdUtil.getStringWidth(td.getContent());
                if (td.getColSpan() > 1) {
                    int realWidth = (int) Math.ceil(width * 1.0 / td.getColSpan());
                    for (int j = 0, span = td.getColSpan(); j < span; j++) {
                        int colIndex = td.getCol() + j;
                        Integer colWidth = colWidthMap.get(colIndex);
                        if (colWidth == null || colWidth < realWidth) {
                            colWidthMap.put(colIndex, realWidth);
                        }
                    }
                } else {
                    colWidthMap.put(td.getCol(), width);
                }
            }
            String widthStr = td.getStyle().get("width");
            if (widthStr != null) {
                int width = TdUtil.getValue(widthStr);
                if (width >= 0) {
                    colWidthMap.put(td.getCol(), width);
                }
            }
        }
        tr.setTdList(tdList);
        tr.setColWidthMap(colWidthMap);
    }

    private void setTdContent(Element tdElement, Td td) {
        Elements imgs = tdElement.getElementsByTag(HtmlTag.img.name());
        if (imgs != null && !imgs.isEmpty()) {
            String src = imgs.get(0).attr("src");
            td.setFile(new File(src));
            td.setTdContentType(ContentTypeEnum.IMAGE);
            return;
        }
        Elements links = tdElement.getElementsByTag(HtmlTag.a.name());
        if (links != null && !links.isEmpty()) {
            Element a = links.get(0);
            td.setContent(a.text());
            String href = a.attr("href").trim();
            td.setLink(href);
            td.setTdContentType(href.startsWith("mailto:") ? ContentTypeEnum.LINK_EMAIL : ContentTypeEnum.LINK_URL);
            return;
        }
        String content = this.parseContent(tdElement, td);
        td.setContent(content);
        if (StringUtil.isBlank(content)) {
            return;
        }
        if (tdElement.hasAttr("string")) {
            return;
        }
        if (tdElement.hasAttr("double")) {
            td.setTdContentType(ContentTypeEnum.DOUBLE);
            td.setContent(RegexpUtil.removeComma(td.getContent()));
            return;
        }
        // 公式设置
        boolean isFormula = tdElement.hasAttr("formula");
        if (isFormula) {
            td.setFormula(true);
            String formula = td.getContent().trim();
            if (formula.startsWith(Constants.EQUAL)) {
                formula = formula.substring(1);
            }
            td.setContent(formula);
            return;
        }
        if (tdElement.hasAttr("url")) {
            String link = tdElement.attr("url");
            td.setTdContentType(ContentTypeEnum.LINK_URL);
            td.setLink(link);
            return;
        }
        if (tdElement.hasAttr("email")) {
            String link = tdElement.attr("email");
            td.setTdContentType(ContentTypeEnum.LINK_EMAIL);
            td.setLink(link);
            return;
        }
        if (tdElement.hasAttr("dropDownList")) {
            td.setTdContentType(ContentTypeEnum.DROP_DOWN_LIST);
            return;
        }
        if (Constants.TRUE.equals(content) || Constants.FALSE.equals(content)) {
            td.setTdContentType(ContentTypeEnum.BOOLEAN);
            return;
        }
        if (DOUBLE_PATTERN.matcher(content).matches()) {
            td.setTdContentType(ContentTypeEnum.DOUBLE);
        }
    }

    private String parseContent(Element tdElement, Td td) {
        Elements spans = tdElement.getElementsByTag(HtmlTag.span.name());
        if (spans != null && !spans.isEmpty()) {
            td.setFonts(new LinkedList<>());
            if (spanText == null) {
                spanText = new XSSFRichTextString("");
            }
            int startIndex = 0;
            for (Element spanElement : spans) {
                String spanContent = spanElement.text();
                if (spanContent == null) {
                    continue;
                }
                spanContent = LINE_FEED_PATTERN.matcher(spanContent).replaceAll("\n");
                spanText.setString(spanContent);
                Font font = new Font();
                font.setStartIndex(startIndex);
                font.setEndIndex(startIndex + spanText.length());

                Map<String, String> fontStyle = StyleUtil.parseStyle(spanElement);
                if (!fontStyle.isEmpty()) {
                    font.setStyle(fontStyle);
                    td.getFonts().add(font);
                }
                startIndex = font.getEndIndex();
            }
        }
        return LINE_FEED_PATTERN.matcher(tdElement.text()).replaceAll("\n");
    }

    public enum HtmlTag {
        /**
         * table
         */
        table,
        /**
         * caption
         */
        caption,
        /**
         * thead
         */
        thead,
        /**
         * tbody
         */
        tbody,
        /**
         * tr
         */
        tr,
        /**
         * th
         */
        th,
        /**
         * td
         */
        td,
        /**
         * colspan
         */
        colspan,
        /**
         * rowspan
         */
        rowspan,
        /**
         * link
         */
        link,
        /**
         * img
         */
        img,
        /**
         * a
         */
        a,
        /**
         * span
         */
        span;
    }
}
