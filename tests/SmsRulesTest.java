import kr.academy.attendance.SmsRules;
import java.util.HashMap;
import java.util.Map;
public class SmsRulesTest {
    private static void equal(Object a,Object b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
    public static void main(String[]args){
        equal(SmsRules.phone("+82 10-1234-5678"),"01012345678");equal(SmsRules.phone("010-1234-5678"),"01012345678");equal(SmsRules.phone("01012345678;01099998888"),"");
        equal(SmsRules.key("s","2026-10-08","present","+82 10-1234-5678"),SmsRules.key("s","2026-10-08","present","01012345678"));
        Map<String,String> values=new HashMap<>();values.put("학생이름","{상태}$학생");values.put("상태","출석");
        equal(SmsRules.render("{학생이름} {상태} {모르는항목}",values),"{상태}$학생 출석 {모르는항목}");
        System.out.println("SmsRules: phone normalization, durable duplicate key and single-pass template tests passed.");
    }
}
