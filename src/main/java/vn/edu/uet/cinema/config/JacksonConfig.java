package vn.edu.uet.cinema.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.module.SimpleModule;

@Configuration(proxyBeanMethods = false)
class JacksonConfig {

	/**
	 * PostgreSQL stores no NUL character (0x00) in a text column, so a request string holding one failed the insert
	 * with a 500. Every JSON string, in any field or list, is checked once here and refused as a 400 naming the field.
	 */
	@Bean
	JacksonModule rejectNulCharacters() {
		return new SimpleModule("reject-nul-characters").addDeserializer(String.class, new NulRejectingStrings());
	}

	static final class NulRejectingStrings extends StringDeserializer {

		@Override
		public String deserialize(JsonParser parser, DeserializationContext context) {
			String value = super.deserialize(parser, context);
			if (value != null && value.indexOf('\0') >= 0) {
				throw context.weirdStringException(value, String.class, "must not contain a NUL character");
			}
			return value;
		}
	}
}
