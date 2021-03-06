package com.fis.webserver.http.impl;

import com.fis.webserver.http.HttpRequestHandler;
import com.fis.webserver.model.http.HttpRequest;
import com.fis.webserver.model.http.HttpResponse;

/**
 * Request handler for the GET http method
 * 
 *
 * 
 * @author Florin Iordache
 *
 */

public class HttpGetResponseHandler extends HttpBaseResponseHandler implements
		HttpRequestHandler {
	
	@Override
	public HttpResponse handle(HttpRequest request) {
		return super.handle(request);
	}

}
