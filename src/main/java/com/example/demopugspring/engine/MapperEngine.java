package com.example.demopugspring.engine;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.demopugspring.engine.operation.AbstractOperation;
import com.example.demopugspring.factory.ContextSingleton;
import com.example.demopugspring.filter.MatchesValueFilter;
import com.example.demopugspring.model.Integration;
import com.example.demopugspring.model.IntegrationMapper;
import com.example.demopugspring.model.Mapper;
import com.example.demopugspring.model.Mapper.Category;
import com.example.demopugspring.operation.ClearFilteredOperation;
import com.example.demopugspring.operation.FieldOperation;
import com.example.demopugspring.operation.ReplaceOperation;
import com.example.demopugspring.operation.SwapOperation;
import com.example.demopugspring.properties.Codes;
import com.example.demopugspring.properties.CountryCodes;
import com.example.demopugspring.properties.FacilitiesCodes;
import com.example.demopugspring.properties.IdentificationCodes;
import com.example.demopugspring.properties.InsurersCodes;
import com.example.demopugspring.properties.MarriageStatusCodes;
import com.example.demopugspring.properties.PropertiesCategoriesEnum;
import com.example.demopugspring.service.ApplicationService;
import com.example.demopugspring.service.IntegrationMapperService;
import com.example.demopugspring.service.IntegrationService;
import com.example.demopugspring.service.MessageService;
import com.example.demopugspring.visitor.MapperVisitor;
import com.example.demopugspring.visitor.TranscodingVisitor;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Segment;
import ca.uhn.hl7v2.model.v24.datatype.CX;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;

@Service
public class MapperEngine {

	private static final Logger log = LoggerFactory.getLogger(MapperEngine.class);

	private static final String SEGMENT_SEPARATOR = "\r";
	private static final String FIELD_SEPARATOR = "|";
	private static final String COMPONENT_SEPARATOR = "^";

	private static final String ESCAPED_CARRIAGE_RETURN = "\\.br\\";

	private static final Pattern HL7_ESCAPED_HEX_PATTERN = Pattern.compile("\\\\X[89A-F][0-9A-F]\\\\");

	private static final int NUMBER_PID_SEQ = 39;
	
	private static final Pattern OBX2_ED_PATTERN = Pattern.compile("(?<=OBX\\|\\d{0,4}\\|)ED");
	private static final Pattern OBX2_PDF_BASE64_PATTERN = Pattern.compile("(?<=OBX\\|\\d{0,4}\\|)PDF_BASE64");
	private static final Pattern OBX5_JVBER_PATTERN = Pattern.compile("(?<=OBX\\|\\d{0,4}\\|ED\\|.{0,250}\\|.{0,20}\\|)JVBER");

	private static final String OPERATIONS_PACKAGE = "operation";

    @Autowired
    IdentificationCodes identificationCodes;
    @Autowired
    FacilitiesCodes facilitiesCodes;
    @Autowired
    CountryCodes countryCodes;
    @Autowired
    MarriageStatusCodes marriageStatusCodes;
    @Autowired
	InsurersCodes insurersCodes;
	@Autowired
    IntegrationService integrationService;
    @Autowired
    IntegrationMapperService integrationMapperService;
    @Autowired
    ApplicationService applicationService;
    @Autowired
    MessageService messageService;

    private void textFields(String field, String text, Terser encodedMessage, Terser decodedMessage) throws HL7Exception {
        MapperVisitor visitor;

        visitor = new MapperVisitor(field, text);
        visitor.start(decodedMessage.getSegment(field.split("-")[0]).getMessage());
    }


    public void transcode(Terser tmp, List<String> keys, String value, List<MapperError> errorList) throws HL7Exception {
        TranscodingVisitor transcodeVisitor;
        Codes codeInterface;
        PropertiesCategoriesEnum property = PropertiesCategoriesEnum.valueOfProperty(value);
        switch (property) {
            case FACILITIES:
                codeInterface = facilitiesCodes;
                break;
            case GH_LOCATIONS:
                codeInterface = countryCodes;
                break;
            case IDENTIFICATIONS:
                codeInterface = identificationCodes;
                break;
            case MARRIAGE_STATUS:
                codeInterface = marriageStatusCodes;
                break;
            case INSURERS_CODES:
				codeInterface = insurersCodes;
            	break;
            default:
                throw new HL7Exception("Transcode propperty is incorrect.");
        }

        for (String key : keys) {
            transcodeVisitor = new TranscodingVisitor(key, value, codeInterface);
            transcodeVisitor.start(tmp.getSegment(key.split("-")[0]).getMessage());
        }


    }

    /**
     * @param msg
     * @param tmp
     * @param fields
     * @param value
     * @param type
     * @param errorList
     */
    void mapper(Terser msg, Terser tmp, List<String> fields, String value, Mapper.Category type, List<MapperError> errorList) {
        fields.forEach(field -> {
            try {
                if (field.contains("#")) {
                    boolean toContinue = true;
                    log.info("Contains # - loop all segments/fields");

                    int i = 0;

                    while (true) {

                        var fieldRep = field.replace("#", String.valueOf(i));

                        var valueRep = value;
                        if (value.equals("#")) {
                            valueRep = String.valueOf(i + 1);
                        } else if (type == Mapper.Category.FIELD) {
                            valueRep = value.replace("#", i + "");
                        }
                        log.info(fieldRep);
                        log.info(valueRep);
                        if (msg.getSegment(fieldRep).isEmpty()) {
                            log.info("Segmento:" + msg.getSegment(fieldRep).encode());
                            log.info("Segment is empty.");
                            break;
                        }
                        switch (type) {
                            case TEXT:
                                tmp.set(fieldRep, valueRep);
                                break;
                            case FIELD:
                                tmp.set(fieldRep, msg.get(valueRep));
                                break;
                            case SWAP:
                                tmp.set(fieldRep, msg.get(valueRep));
                                tmp.set(valueRep, msg.get(fieldRep));
                                break;
                            default:
                                log.error("No defined Category");
                                errorList.add(new MapperError(field, "No Category defined as: " + type));
                        }
                        i++;
                        break;
                    }
                } else {
                    log.info("No # on field - just a simple map");
                    switch (type) {
                        case TEXT:
                            tmp.set(field, value);
                            //textFields(field, value, msg, tmp);
                            break;
                        case FIELD:
                            tmp.set(field, msg.get(value));
                            break;
                        case SWAP:
                            tmp.set(field, msg.get(value));
                            tmp.set(value, msg.get(field));
                            break;
                        case SEGMENT:
						tmp.getSegment(field).parse(value);
                            break;
                        case JOIN:
                            StringBuilder joined = new StringBuilder();
                            String valueToAppend;
                            log.info("Fields to join:" + value);
                            for (String val : value.split(",")) {
								valueToAppend = msg.get(val);
								log.info("Value for " + val + ":" + valueToAppend);
								joined.append(valueToAppend);
                            }
                            log.info("joined fields:" + joined.toString());
                            tmp.set(field, joined.toString());
                            break;
                        case NUMERIC:
                            tmp.set(field, msg.get(field).replaceAll("[^\\d.]", ""));
                            break;
                        default:
                            log.error("No defined category");
                            errorList.add(new MapperError(field, "No Category defined as " + type));
                    }
                }
            } catch (HL7Exception ex) {
                log.error("Error on HL7 mapping", ex);
                errorList.add(new MapperError(field, ex.getMessage()));
            }
        });
    }

    public void swapAfterOperation(Terser tmp, List<String> fields, String value, Mapper.Category type, List<MapperError> errorList) {
        SwapOperation swapOperation = new SwapOperation(value, fields);
        try {
            swapOperation.doOperation(tmp.getSegment(value.split("-")[0]).getMessage());
        } catch (HL7Exception e) {
            errorList.add(new MapperError(e.getError().name(), e.getDetail().toString()));
        }
    }

    public void fieldAfterOperation(Terser msg, Terser tmp, List<String> fields, String value, Mapper.Category type, List<MapperError> errorList) {
        FieldOperation fieldOperation = new FieldOperation(value, fields);
        try {
            fieldOperation.doOperation(tmp.getSegment(value.split("-")[0]).getMessage());
        } catch (HL7Exception e) {
            errorList.add(new MapperError(e.getError().name(), e.getDetail().toString()));
        }
    }

    public void clearIfOperation(Terser tmp, List<String> fields, String value, List<MapperError> errorList) {
        try {
            ClearFilteredOperation clearOperation = new ClearFilteredOperation(value, fields, new MatchesValueFilter(value, tmp));
            clearOperation.doOperation(tmp.getSegment(fields.get(0).split("-")[0]).getMessage());
        } catch (HL7Exception e) {
            errorList.add(new MapperError(e.getError().name(), e.getDetail().toString()));
        }
    }

    public void replaceOperation(Terser tmp, List<String> fields, String value, List<MapperError> errorList) {
        ReplaceOperation replaceOperation = new ReplaceOperation(value, fields);
        try {
            replaceOperation.doOperation(tmp.getSegment(fields.get(0).split("-")[0]).getMessage());
        } catch (HL7Exception e) {
            errorList.add(new MapperError(e.getError().name(), e.getDetail().toString()));
        }
    }

    public void addContactRepetitions(Terser tmp, String field, String... strings) throws HL7Exception {
        int i = 0;
		final Pattern EMAIL_REGEX = Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", Pattern.CASE_INSENSITIVE);

        for (String s : strings) {
            if (StringUtils.isEmpty(s)) {
                continue;
            }
			boolean isEmail = EMAIL_REGEX.matcher(s).matches();
			if (!isEmail) {
                tmp.set(field + "-13(" + i + ")-3", "PH");
                tmp.set(field + "-13(" + i + ")-12", s);
                tmp.set(field + "-13(" + i + ")-4", "");
                tmp.set(field + "-13(" + i + ")-7", "");

            } else {
                tmp.set(field + "-13(" + i + ")-3", "X.400");
                tmp.set(field + "-13(" + i + ")-4", s);
                tmp.set(field + "-13(" + i + ")-12", "");
            }
            tmp.set(field + "-14-7", "");

            i++;
        }
    }

	public void textIf(Terser msg, Terser tmp, List<String> fields, String value, List<MapperError> errorList) throws HL7Exception {
		String key = fields.get(0);
		String ifKey = fields.get(1);
		String ifRegex = fields.size() > 2 ? fields.get(2) : "";

		String ifValue = Optional.ofNullable(tmp.get(ifKey)).orElse("");

		if (ifValue.matches(ifRegex)) {
			tmp.set(key, value);
		}
	}

    public Response run(String incomingMessage) {
        String result = "";
        Response response = new Response();
        List<MapperError> errorList = new ArrayList<>();
        HapiContext context = ContextSingleton.getInstance();
        PipeParser parser = context.getPipeParser();
        try {
            // Transforming the string before parsing to a HL7v2 Message
			incomingMessage = fixMessage(incomingMessage);
            Message message = parser.parse(incomingMessage);
            Message volatileMessage = parser.parse(incomingMessage);
            log.info("Incoming message version:" + message.getVersion());
            Terser msg = new Terser(message);
            Terser volatileTerser = new Terser(volatileMessage);
            String messageCode = msg.get("MSH-9-1");
            String messageEvent = msg.get("MSH-9-2");
            String sendingApp = msg.get("MSH-3-1");
            String receivingApp = msg.get("MSH-5-1");
            String messageVersion = msg.get("MSH-12");

            Integration integration = integrationService.findByMessageAndApplications(
                    messageService.find(messageCode, messageEvent, messageVersion),
                    applicationService.findByCode(sendingApp),
                    applicationService.findByCode(receivingApp));

            if (integration == null) {
                throw new HL7Exception("No integration found for message " + messageCode + "-" + messageEvent +
                        " and sending application " + sendingApp + " and receiving application " + receivingApp);
            }
            List<IntegrationMapper> integrationMappers = integrationMapperService.retrieveActiveIntegrationMappersFromIntegration(integration);
            List<Mapper> mappers = new ArrayList<>();

            for(IntegrationMapper intMapper : integrationMappers){
                mappers.add(intMapper.getMapper());
            }
            log.info("Integration:" + mappers.toString());
            // Change message version
            volatileTerser.set("MSH-9-1", integration.getResultMessage().getCode());
            volatileTerser.set("MSH-9-2", integration.getResultMessage().getEvent());
            volatileTerser.set("MSH-12", integration.getResultMessage().getVersion().getValue());
			volatileTerser.set("MSH-18", "8859/1");
            Message outMessage = parser.parse(volatileMessage.encode());
            log.info(outMessage.getName() + " " + outMessage.getVersion());
            Terser tmp = new Terser(outMessage);
            for (Mapper mapper : mappers) {
                Category mapperCategory = mapper.getCategory();
                List<Category> supportedOperations = Arrays.asList(Category.TEXT, Category.FIELD);
                if (supportedOperations.contains(mapperCategory)) {
                    String mapperClassName = this.getClass().getPackageName() + "." + OPERATIONS_PACKAGE + "." + mapperCategory.getValue();

                    AbstractOperation mapperInstance = null;
                    try {
                        log.debug("Searching for class {}...", mapperClassName);
                        @SuppressWarnings("unchecked")
                        Class<? extends AbstractOperation> mapperClass = (Class<? extends AbstractOperation>) Class.forName(mapperClassName);

                        Constructor<? extends AbstractOperation> mapperConstructor = mapperClass.getConstructor(this.getClass(), Message.class, Message.class,
                                Terser.class, Terser.class, List.class, String.class);

                        mapperInstance = mapperConstructor.newInstance(this, message, outMessage, msg, tmp, mapper.getKey(), mapper.getValue());

                        mapperInstance.map();
                        errorList.addAll(mapperInstance.getErrors());
                    } catch (ClassNotFoundException e) {
                        log.error("Couldn't find class for Mapper category '{}' ('{}')!", mapperCategory, mapperClassName, e);
                        errorList.add(new MapperError("Global", "Mapper category " + mapperCategory + " isn't supported!"));
                    } catch (NoSuchMethodException | SecurityException e) {
                        log.error("Couldn't find right constructor for '{}'!", mapperClassName, e);
                        errorList.add(new MapperError("Global", "Mapper category " + mapperCategory + " isn't supported!"));
                    } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                        log.error("Couldn't instantiate '{}'!", mapperClassName, e);
                        errorList.add(new MapperError("Global", "Mapper category " + mapperCategory + " isn't supported!"));
                    } catch (Exception e) {
                        log.error("Unexpected exception setting or running Mapper '{}'!", mapperClassName, e);
                        errorList.add(new MapperError("Global", "Unexpected setting or running Mapper '" + mapperClassName + "'!"));
                    }
                } else {
                    switch (mapperCategory) {
                        case TEXT:
                        case FIELD:
                        case SWAP:
                        case SEGMENT:
                        case JOIN:
                        case NUMERIC:
                            log.info("TEXT or FIELD");
                            mapper(msg, tmp, mapper.getKey(), mapper.getValue(), mapperCategory, errorList);
                            break;
                        case CONTACT:
                            String field = mapper.getKey().get(0);
                            addContactRepetitions(tmp, field, tmp.get(field + "-13-7-1"), tmp.get(field + "-13-12-1"), tmp.get(field + "-14-7-1"), tmp.get(field + "-14-12-1"), tmp.get(field + "-13-04"));
                            break;
                        case ADD_SNS:
                            addFieldSNS(tmp, msg, mapper.getKey(), mapper.getValue(), errorList);
                            break;
                        case AFTER_JOIN_FIELDS:
                            joinFields(tmp, mapper.getKey(), mapper.getValue(), errorList);
                            break;
                        case AFTER_FIELD:
                            fieldAfterOperation(msg, tmp, mapper.getKey(), mapper.getValue(), mapperCategory, errorList);
                            break;
                        case AFTER_SWAP:
                            swapAfterOperation(tmp, mapper.getKey(), mapper.getValue(), mapperCategory, errorList);
                            break;
                        case TRANSCODING:
                            transcode(tmp, mapper.getKey(), mapper.getValue(), errorList);
                            break;
                        case CLEAR_IF:
                            clearIfOperation(tmp, mapper.getKey(), mapper.getValue(), errorList);
                            break;
                        case REPLACE:
                            replaceOperation(tmp, mapper.getKey(), mapper.getValue(), errorList);
                            break;
					case TEXT_IF:
						textIf(msg, tmp, mapper.getKey(), mapper.getValue(), errorList);
						break;
                        default:
                            errorList.add(new MapperError(mapper.getKey().toString(), "No Category: " + mapperCategory));
                    }
                }
            }
            log.info("Out message version:" + outMessage.getName() + " " + outMessage.getVersion());
            result = outMessage.encode();
            result = cleanMessage(result);
            log.info(result);
        } catch (HL7Exception ex) {
            log.error(ex.getMessage());
            errorList.add(new MapperError("Global", ex.getMessage()));
        }
        response.setMessage(result);
        response.setErrorList(errorList);
        return response;
    }

    public void joinFields(Terser tmp, List<String> key, String value, List<MapperError> errorList) throws HL7Exception {
        String[] field_split = key.get(0).split("-");
        String[] value_split = value.split("-");
        Segment segmentTarget = tmp.getSegment(field_split[0]);

        int numberRepTarget = tmp.getSegment(field_split[0]).getField(Integer.valueOf(field_split[1])).length;
        int numberRepSource = tmp.getSegment(value_split[0]).getField(Integer.valueOf(value_split[1])).length;
        int totalRepetitions = (numberRepTarget + numberRepSource);
        int indexRepTarget = numberRepTarget;
        int indexRepSource = 0;

        while (indexRepTarget < totalRepetitions) {
            while (indexRepSource < numberRepSource) {
                int indexFields = 1;
                int numFieldsSource = ((CX) segmentTarget.getField(Integer.valueOf(field_split[1]), indexRepSource)).getComponents().length;
                while (indexFields < numFieldsSource + 1) {
                    String valueToPass = Terser.get(segmentTarget, Integer.valueOf(value_split[1]), indexRepSource, indexFields, 1);
                    Terser.set(segmentTarget, Integer.valueOf(field_split[1]), indexRepTarget, indexFields, 1, valueToPass);
                    Terser.set(tmp.getSegment(value_split[0]), Integer.valueOf(value_split[1]), indexRepSource, indexFields, 1, null);
                    indexFields++;
                }
                indexRepTarget++;
                indexRepSource++;
            }

        }
    }

    public void addFieldSNS(Terser tmp, Terser msg, List<String> key, String value, List<MapperError> errorList) throws HL7Exception {
		if (!StringUtils.isEmpty(msg.get(value))) {
			String[] field_split = key.get(0).split("-");
			Segment segmentTarget = tmp.getSegment(field_split[0]);

			int numberRepTarget = tmp.getSegment(field_split[0]).getField(Integer.valueOf(field_split[1])).length;
			Terser.set(segmentTarget, Integer.valueOf(field_split[1]), numberRepTarget, 1, 1, msg.get(value));
			Terser.set(segmentTarget, Integer.valueOf(field_split[1]), numberRepTarget, 4, 1, "SNS");
		}
	}

    private String cleanMessage(String message) {
		String messageContent = message.replaceAll("~(~)+", "~");
		messageContent = messageContent.replace("|~", FIELD_SEPARATOR);
		messageContent = messageContent.replace("~|", FIELD_SEPARATOR);
		messageContent = messageContent.replace("~\r", "");
		return messageContent;
    }

	/**
	 * Returns a new string which, by applying some needed general fixes or ones
	 * specific to each segment, should now be valid when parsing into an Hapi
	 * HL7v2 Message.
	 * 
	 * @param message
	 *            the content of an HL7v2 message.
	 * @return a valid message, ready to be parsed.
	 */
	public static String fixMessage(String message) {
		// Replace all LF (0x0A) characters with HL7-escaped CR sequence,
		// "\.br\"
		message = message.replace("\n", ESCAPED_CARRIAGE_RETURN);

		// Unescapes all HL7-escaped hexadecimal sequences greater than decimal
		// 127, "\X80\"
		message = unescapeNonASCIISequences(message);

		// Iterate each segment and apply specific fixes
		StringTokenizer messageTokenizer = new StringTokenizer(message, SEGMENT_SEPARATOR);
		String segment = "";
		StringBuilder newMessageBuilder = new StringBuilder();

		while (messageTokenizer.hasMoreTokens()) {
			segment = messageTokenizer.nextToken();
			if (Character.isWhitespace(segment.charAt(0)))
				segment = segment.stripLeading();

			switch (segment.substring(0, 3)) {
			case "PID":
				newMessageBuilder.append(fixPID(segment));
				break;
			case "OBX":
				newMessageBuilder.append(fixOBX(segment));
				break;
			default:
				newMessageBuilder.append(segment);
			}

			newMessageBuilder.append(SEGMENT_SEPARATOR);
		}

		return newMessageBuilder.toString();
	}

	private static String fixPID(String segment) {
		String[] fields = segment.split("\\" + FIELD_SEPARATOR);

		StringBuilder newSegmentBuilder = new StringBuilder();
		for (int i = 0; i < fields.length; i++) {
			switch (i) {
			case 13:
				newSegmentBuilder.append(getFixContactPhone(fields[i]));
				break;
			case 14:
				newSegmentBuilder.append(getFixContactPhone(fields[i]));
				break;
			default:
				newSegmentBuilder.append(fields[i]);
			}

			newSegmentBuilder.append(FIELD_SEPARATOR);
		}

		return newSegmentBuilder.toString();
	}

	/**
	 * Swap Field PID-13-7 to PID-13-12. PID-13-7 doesn't support non digits. So
	 * it's necessary a swap between PID-13-7 and PID-13-12. This function could
	 * be applied to PID-14-7.
	 * 
	 * @param field13Old
	 *            The field PID-13(0)
	 * @return
	 */
	private static String getFixContactPhone(String field13Old) {
		String[] field13NewArray = new String[NUMBER_PID_SEQ];
		int i = 0;
		String[] field13OldTokenizer = field13Old.split("\\" + COMPONENT_SEPARATOR);
		for (String component : field13OldTokenizer) {
			field13NewArray[i] = component;
			i++;
		}
		if (!StringUtils.isEmpty(field13NewArray[7 - 1])) {
			field13NewArray[12 - 1] = field13NewArray[7 - 1];
			field13NewArray[7 - 1] = StringUtils.EMPTY;
		}
		StringBuilder field13New = new StringBuilder();
		for (String field : field13NewArray) {
			field13New.append(field == null ? StringUtils.EMPTY : field);
			field13New.append(COMPONENT_SEPARATOR);
		}
		return field13New.toString().replaceAll("\\^{2,}$", "");
	}


	/**
	 * Returns a new string which, by fixing the OBX-2 and OBX-5 fields, should
	 * now be valid when parsing.
	 * 
	 * @param segment
	 *            the OBX segment to be fixed.
	 * @return a valid OBX segment, ready to be parsed.
	 */
	public static String fixOBX(String segment) {
		String newSegment = OBX2_ED_PATTERN.matcher(segment).replaceFirst("TX");
		newSegment = OBX2_PDF_BASE64_PATTERN.matcher(newSegment).replaceFirst("ED");
		return OBX5_JVBER_PATTERN.matcher(newSegment).replaceFirst("^^^^JVBER");
	}

	/**
	 * Converts all HL7-escaped hexadecimal sequences with code greater than 127
	 * found in {@link str} to ASCII.
	 * 
	 * @param str
	 *            the string to be converted
	 * @return the converted string
	 */
	public static String unescapeNonASCIISequences(String str) {
		StringBuffer newString = new StringBuffer();
		Matcher matcher = HL7_ESCAPED_HEX_PATTERN.matcher(str);
		while (matcher.find()) {
			matcher.appendReplacement(newString, String.valueOf(unescapeNonASCIIChar(matcher.group())));
		}
		matcher.appendTail(newString);

		return newString.toString();
	}

	/**
	 * Converts an HL7-escaped hexadecimal sequence to ASCII.
	 * 
	 * @param str
	 *            the string sequence to be converted
	 * @return the converted char
	 */
	private static char unescapeNonASCIIChar(String str) {
		return (char) Integer.parseInt(str.substring(2, 4), 16);
	}
}
