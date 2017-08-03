/*
 * The MIT License
 *
 * Copyright 2013 Karol Bucek.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package arjdbc.mssql;

import static arjdbc.util.QuotingUtils.BYTES_0;
import static arjdbc.util.QuotingUtils.BYTES_1;
import static arjdbc.util.QuotingUtils.quoteCharAndDecorateWith;
import static arjdbc.util.QuotingUtils.quoteSingleQuotesWithFallback;

import org.jruby.Ruby;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

/**
 * ArJdbc::MSSQL
 *
 * @author kares
 */
@org.jruby.anno.JRubyModule(name = "ArJdbc::MSSQL")
public class MSSQLModule {

    public static RubyModule load(final RubyModule arJdbc) {
        RubyModule mssql = arJdbc.defineModuleUnder("MSSQL");
        mssql.defineAnnotatedMethods( MSSQLModule.class );
        return mssql;
    }

    public static RubyModule load(final Ruby runtime) {
        return load( arjdbc.ArJdbcModule.get(runtime) );
    }

    @JRubyMethod(name = "quote_string", required = 1)
    public static IRubyObject quote_string(final ThreadContext context,
        final IRubyObject self, final IRubyObject string) {
        return quoteSingleQuotesWithFallback(context, string);
    }

    @JRubyMethod(name = "quoted_true", required = 0)
    public static IRubyObject quoted_true(final ThreadContext context,
        final IRubyObject self) {
        return RubyString.newString(context.runtime, BYTES_1);
    }

    @JRubyMethod(name = "quoted_false", required = 0)
    public static IRubyObject quoted_false(final ThreadContext context,
        final IRubyObject self) {
        return RubyString.newString(context.runtime, BYTES_0);
    }

    // part =~ /^\[.*\]$/ ? part : "[#{part.gsub(']', ']]')}]"
    @SuppressWarnings("deprecation")
    @JRubyMethod(required = 1, visibility = Visibility.PRIVATE)
    public static IRubyObject quote_name_part(final ThreadContext context,
        final IRubyObject self, final IRubyObject part) {

        final RubyString partString = (RubyString) part;
        final ByteList str = partString.getByteList();
        if ( str.charAt(0) == '[' && str.charAt(str.length() - 1) == ']' ) {
            return part; // part =~ /^\[.*\]$/ ? part
        }

        return quoteCharAndDecorateWith(context, partString, ']', ']', (byte) '[', (byte) ']');
    }

}
