"""Korean public holidays and the additional closures in the 2027 production plan."""
from datetime import date
from functools import lru_cache

from holidays import country_holidays


# Public dates already have a more specific name (e.g. 설날, 제헌절 대체공휴일).
# The schedule also has its own display name for overlapping days off.
COMPANY_CLOSURES_2027 = (
    (1, 2, 2, "신정휴무"),
    (2, 10, 10, "중복휴일"),
    (5, 3, 3, "중복휴일"),
    (7, 19, 19, "중복휴일"),
    (7, 25, 25, "노조창립일"),
    (7, 26, 26, "중복휴일"),
    (8, 2, 6, "하기휴무"),
    (8, 16, 16, "중복휴일"),
    (9, 17, 17, "추석휴무"),
    (10, 4, 4, "중복휴일"),
    (10, 11, 11, "중복휴일"),
    (12, 27, 27, "중복휴일"),
    (12, 29, 29, "회사창립일"),
)


@lru_cache(maxsize=32)
def holidays_for_year(year):
    public = country_holidays("KR", years=year, language="ko", expand=False)
    result = {}
    for day, name in public.items():
        if name == "신정연휴" and day.day == 1:
            name = "신정"
        result[day.isoformat()] = name.replace("대체 휴일", "대체공휴일")
    if year == 2027:
        for month, start, end, reason in COMPANY_CLOSURES_2027:
            for day in range(start, end + 1):
                result[date(year, month, day).isoformat()] = reason
    return result
