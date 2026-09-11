package com.xempastissimo.lightnovelreader.data.source.wenku8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookshelf page, against a capture of the real markup.
 *
 * This page is the one place where guessing went wrong twice, so it is pinned down
 * properly: removal is a *form* (plus a `javascript:` per-row control), and the bookshelf
 * numbers its rows differently from every other page in the site.
 */
class BookcasePageTest {

    private val page = """
        <div id="centerm"><div id="content">
        <script language="javascript">
        function check_confirm(){
        	var checkform = document.getElementById('checkform');
        	var checknum = 0;
        	for (var i=0; i < checkform.elements.length; i++){
        	 if (checkform.elements[i].name == 'checkid[]' && checkform.elements[i].checked == true) checknum++;
        	}
        	if(checknum == 0){
        		alert('请先选择要操作的书目！');
        		return false;
        	}
        	return true;
        }
        </script>
        <form action="" method="post" name="checkform" id="checkform" onsubmit="return check_confirm();">
        <div class="gridtop">
        您的书架可收藏 300 本，已收藏 3 本，本组有 3 本。

        &nbsp;&nbsp;&nbsp;&nbsp;选择分组
          <select name="classlist" onchange="javascript:document.location='bookcase.php?classid='+this.value;">
            <option value="0" selected="selected">默认书架</option>

            <option value="1">第1组书架</option>

          </select>

          </div>
        <table class="grid" width="100%" align="center">
          <tbody><tr align="center">
            <th width="3%"><input type="checkbox" id="checkall" name="checkall" value="checkall"></th>
            <th width="19%">名称</th>
            <th width="9%">作者</th>
            <th width="30%">最新章节</th>
            <th width="25%">书签</th>
            <th width="9%">更新</th>
            <th width="5%">操作</th>
          </tr>

          <tr>
            <td class="odd" align="center">
        	<input type="checkbox" id="checkid[]" name="checkid[]" value="13066825">    </td>
            <td class="even"><a href="https://www.wenku8.net/modules/article/readbookcase.php?aid=3988&amp;bid=13066825" target="_blank">玩玩的恋爱关系(玩乐关系)</a></td>
            <td class="odd"><a href="authorarticle.php?author=葵关南">葵关南</a></td>
            <td class="even"><a href="https://www.wenku8.net/modules/article/readbookcase.php?aid=3988&amp;bid=13066825&amp;cid=178729" target="_blank">蜜瓜特典 朋友以上的证明</a>
        	</td>
            <td class="odd"><a href="#" target="_blank"></a></td>
            <td class="odd" align="center">26-09-09
        	</td>
            <td class="even" align="center"><a href="javascript:if(confirm('确实要将本书移出书架么？')) document.location='/modules/article/bookcase.php?delid=13066825';">移除</a></td>
          </tr><tr>
            <td class="odd" align="center">
        	<input type="checkbox" id="checkid[]" name="checkid[]" value="13078679">    </td>
            <td class="even"><a href="https://www.wenku8.net/modules/article/readbookcase.php?aid=1973&amp;bid=13078679" target="_blank">欢迎来到实力至上主义的教室</a></td>
            <td class="odd"><a href="authorarticle.php?author=衣笠彰梧">衣笠彰梧</a></td>
            <td class="even"><a href="https://www.wenku8.net/modules/article/readbookcase.php?aid=1973&amp;bid=13078679&amp;cid=176596" target="_blank">漫画特别特典 纪念派对</a>
        	</td>
            <td class="odd"><a href="#" target="_blank"></a></td>
            <td class="odd" align="center">26-06-26
        	</td>
            <td class="even" align="center"><a href="javascript:if(confirm('确实要将本书移出书架么？')) document.location='/modules/article/bookcase.php?delid=13078679';">移除</a></td>

          </tr><tr>
            <td class="odd" align="center">
        	<input type="checkbox" id="checkid[]" name="checkid[]" value="9169147">    </td>
            <td class="even"><a href="https://www.wenku8.net/modules/article/readbookcase.php?aid=2536&amp;bid=9169147" target="_blank">这个勇者明明超TUEEE却过度谨慎</a></td>
            <td class="odd"><a href="authorarticle.php?author=土日月">土日月</a></td>
            <td class="even"><a href="https://www.wenku8.net/modules/article/readbookcase.php?aid=2536&amp;bid=9169147&amp;cid=116120" target="_blank">插图</a>
        	</td>
            <td class="odd"><a href="#" target="_blank"></a></td>
            <td class="odd" align="center">21-02-06
        	</td>
            <td class="even" align="center"><a href="javascript:if(confirm('确实要将本书移出书架么？')) document.location='/modules/article/bookcase.php?delid=9169147';">移除</a></td>
          </tr>
        <tr>
            <td colspan="6" align="center" class="foot">选中项目
        	<select name="newclassid" id="newclassid">
        	<option value="-1" selected="selected">移出书架</option>
        	<option value="0">移到默认书架</option>

            <option value="1">移到第1组书架</option>

          </select> <input name="btnsubmit" type="submit" value=" 确认 " class="button"><input name="clsssid" type="hidden" value="0"></td>
            </tr>
        </tbody></table>
        </form>

        </div>
        </div>
    """.trimIndent()

    @Test
    fun `lists every row with its author and latest chapter`() {
        val books = Wenku8Parser.parseBookcase(page)

        // The book id is the `aid`, the same id the detail and reader URLs use.
        assertEquals(listOf(3988, 1973, 2536), books.map { it.bookId })
        assertEquals("葵关南", books[0].author)
        assertEquals("衣笠彰梧", books[1].author)
        assertEquals("土日月", books[2].author)
        assertEquals("玩玩的恋爱关系(玩乐关系)", books[0].title)
        // The second link in a row is the 最新章节 one, not a duplicate of the title.
        assertEquals("蜜瓜特典 朋友以上的证明", books[0].latestChapter)
        assertEquals("插图", books[2].latestChapter)
    }

    /**
     * The mapping that made removal fail: the shelf's own id is a *different* number from
     * the book id the rest of the app uses, and only the former is accepted.
     */
    @Test
    fun `maps each book id to the bookshelf's own row id`() {
        val rowIds = Wenku8Parser.parseBookcaseRowIds(page)

        assertEquals("13066825", rowIds[3988])
        assertEquals("13078679", rowIds[1973])
        assertEquals("9169147", rowIds[2536])
        assertEquals(3, rowIds.size)
    }

    @Test
    fun `reads the bulk action form off the page`() {
        val form = Wenku8Parser.parseBookcaseActionForm(page)

        assertNotNull("the footer form must be found", form)
        // `action=""` means "post back here"; it must not resolve to nothing.
        assertEquals(Wenku8Urls.BOOKCASE, form!!.action)
        assertEquals("checkid[]", form.selectionField)
        assertEquals("newclassid", form.actionField)
        assertEquals("btnsubmit", form.submitField)
        assertEquals("-1", Wenku8Selectors.BOOKCASE_CLASS_REMOVE)
        // `clsssid` is the group the page is showing and has to be posted back.
        assertEquals("0", form.hidden["clsssid"])
        // The group switcher is a navigation control, not part of the action.
        assertTrue("classlist must not be submitted", "classlist" !in form.hidden)
    }

    @Test
    fun `reads the real totals from the header`() {
        val summary = Wenku8Parser.parseBookcaseSummary(page)

        assertNotNull(summary)
        assertEquals(300, summary!!.capacity)
        assertEquals(3, summary.total)
        assertEquals(3, summary.inGroup)
    }

    @Test
    fun `a page without the footer form is reported rather than guessed at`() {
        val stripped = page.replace("newclassid", "somethingelse")

        assertEquals(null, Wenku8Parser.parseBookcaseActionForm(stripped))
    }
}
