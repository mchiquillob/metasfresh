/*
 * #%L
 * de-metas-camel-leichundmehl
 * %%
 * Copyright (C) 2022 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

package de.metas.camel.externalsystems.leichundmehl.to_leichundmehl.pporder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.metas.camel.externalsystems.common.JsonObjectMapperHolder;
import de.metas.camel.externalsystems.leichundmehl.to_leichundmehl.tcp.ConnectionDetails;
import de.metas.common.externalsystem.ExternalSystemConstants;
import de.metas.common.externalsystem.JsonExternalSystemLeichMehlConfigProductMapping;
import de.metas.common.util.Check;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
public class ExportPPOrderHelper
{
	@NonNull
	public ConnectionDetails getTcpConnectionDetails(@NonNull final Map<String, String> params)
	{
		final String tcpPortNumber = params.get(ExternalSystemConstants.PARAM_TCP_PORT_NUMBER);
		if (Check.isBlank(tcpPortNumber))
		{
			throw new RuntimeException("Missing mandatory param: " + ExternalSystemConstants.PARAM_TCP_PORT_NUMBER);
		}

		final String tcpHost = params.get(ExternalSystemConstants.PARAM_TCP_HOST);
		if (Check.isBlank(tcpHost))
		{
			throw new RuntimeException("Missing mandatory param: " + ExternalSystemConstants.PARAM_TCP_HOST);
		}

		return ConnectionDetails.builder()
				.tcpPort(Integer.parseInt(tcpPortNumber))
				.tcpHost(tcpHost)
				.build();
	}

	@NonNull
	public JsonExternalSystemLeichMehlConfigProductMapping getProductMapping(@NonNull final Map<String, String> params)
	{
		final String productMapping = params.get(ExternalSystemConstants.PARAM_CONFIG_MAPPINGS);
		if (Check.isBlank(productMapping))
		{
			throw new RuntimeException("Missing mandatory param: " + ExternalSystemConstants.PARAM_CONFIG_MAPPINGS);
		}

		final ObjectMapper mapper = JsonObjectMapperHolder.sharedJsonObjectMapper();

		try
		{
			return mapper.readValue(productMapping, JsonExternalSystemLeichMehlConfigProductMapping.class);
		}
		catch (final JsonProcessingException e)
		{
			throw new RuntimeException(e);
		}
	}
}
