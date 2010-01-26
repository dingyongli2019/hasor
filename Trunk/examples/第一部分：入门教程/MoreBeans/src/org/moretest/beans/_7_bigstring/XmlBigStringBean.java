/*
 * Copyright 2008-2009 the original author or authors.
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
package org.moretest.beans._7_bigstring;
/**
 * 注解方式配置该Bean，并且注入基本属性类型数据。
 * @version 2010-1-20
 * @author 赵永春 (zyc@byshell.org)
 */
public class XmlBigStringBean {
    private String bigString;
    public void setBigString(String bigString) {
        this.bigString = bigString;
    }
};