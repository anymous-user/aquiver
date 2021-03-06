/*
 * MIT License
 *
 * Copyright (c) 2019 1619kHz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.aquiver.handler;

import org.apache.commons.lang3.Validate;
import org.apex.ApexContext;
import org.aquiver.RequestContext;

import java.util.HashMap;
import java.util.Map;

/**
 * @author WangYi
 * @since 2020/7/3
 */
public class ErrorHandlerResolver {
  private final Map<Class<? extends Throwable>, ErrorHandler> exceptionHandlerMap = new HashMap<>();
  private final ApexContext context = ApexContext.instance();

  public void registerErrorHandler(Class<? extends Throwable> throwableCls, ErrorHandler errorHandler) {
    Validate.notNull(throwableCls, "throwableCls can' be null");
    Validate.notNull(errorHandler, "errorHandler can' be null");
    this.exceptionHandlerMap.put(throwableCls, errorHandler);
  }

  public void handleException(Throwable throwable, RequestContext requestContext) {
    if (exceptionHandlerMap.isEmpty() || !exceptionHandlerMap.containsKey(throwable.getClass())) {
      requestContext.closeChannel();
    }
    try {
      final ErrorHandler errorHandler = exceptionHandlerMap.get(throwable.getClass());
      this.context.addBean(context);
      errorHandler.handle(throwable);
    } catch (Exception e) {
      e.printStackTrace();
      requestContext.channelContext().close();
    }
  }
}
